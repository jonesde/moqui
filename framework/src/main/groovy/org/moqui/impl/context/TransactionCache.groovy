/*
 * This software is in the public domain under CC0 1.0 Universal plus a Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.context

import groovy.transform.CompileStatic
import org.apache.commons.collections.map.ListOrderedMap
import org.moqui.context.TransactionException
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityException
import org.moqui.entity.EntityValue
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.entity.EntityFindBase
import org.moqui.impl.entity.EntityListImpl
import org.moqui.impl.entity.EntityValueBase
import org.moqui.impl.entity.EntityValueImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.transaction.Status
import javax.transaction.Synchronization
import javax.transaction.Transaction
import javax.transaction.TransactionManager
import javax.transaction.xa.XAException
import java.sql.Connection

/** This is a per-transaction cache that basically pretends to be the database for the scope of the transaction.
 * Test your code well when using this as it doesn't support everything.
 *
 * Some known limitations:
 * - find list and iterate don't cache results (but do filter and add to results aside from limitations below)
 * - EntityListIterator.getPartialList() and iterating through results with next/previous does not add created values
 * - find with DB limit will return wrong number of values if deleted values were in the results
 * - find count doesn't add for created values, subtract for deleted values, and for updates if old matched and new doesn't subtract and vice-versa
 * - view-entities won't work, they don't incorporate results from TX Cache
 * - if a value is created or update, then a record with FK is created, then the value is updated again commit writes may fail with FK violation (see update() method for other notes)
 */
@CompileStatic
class TransactionCache implements Synchronization {
    protected final static Logger logger = LoggerFactory.getLogger(TransactionCache.class)
    public enum WriteMode { CREATE, UPDATE, DELETE }

    protected ExecutionContextFactoryImpl ecfi

    protected Transaction tx = null

    protected Map<Map, EntityValueBase> readOneCache = [:]
    protected Map<String, Map<EntityCondition, EntityListImpl>> readListCache = [:]

    protected ListOrderedMap writeInfoList = new ListOrderedMap()
    protected Map<String, Map<Map, EntityValueBase>> createByEntityRef = [:]

    TransactionCache(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
    }

    TransactionCache enlist() {
        // logger.warn("========= Enlisting new TransactionCache")
        TransactionManager tm = ecfi.getTransactionFacade().getTransactionManager()
        if (tm == null || tm.getStatus() != Status.STATUS_ACTIVE) throw new XAException("Cannot enlist: no transaction manager or transaction not active")
        Transaction tx = tm.getTransaction()
        if (tx == null) throw new XAException(XAException.XAER_NOTA)
        this.tx = tx

        if (ecfi.getTransactionFacade().getActiveSynchronization("TransactionCache")) {
            logger.warn("Tried to enlist TransactionCache in current transaction but one is already in place, not enlisting", new TransactionException("TransactionCache already in place"))
            return
        }
        // logger.warn("================= putting and enlisting new TransactionCache")
        ecfi.getTransactionFacade().putAndEnlistActiveSynchronization("TransactionCache", this)

        return this
    }

    Map<Map, EntityValueBase> getCreateByEntityMap(String entityName) {
        Map createMap = createByEntityRef.get(entityName)
        if (createMap == null) {
            createMap = [:]
            createByEntityRef.put(entityName, createMap)
        }
        return createMap
    }

    static Map makeKey(EntityValueBase evb) {
        if (evb == null) return null
        Map key = evb.getPrimaryKeys()
        if (!key) return null
        key.put("_entityName", evb.getEntityName())
        return key
    }
    static Map makeKeyFind(EntityFindBase efb) {
        // NOTE: this should never come in null (EntityFindBase.one() => oneGet() => this is only call path)
        if (efb == null) return null
        Map key = efb.getSimpleMapPrimaryKeys()
        if (!key) return null
        key.put("_entityName", efb.getEntityDef().getFullEntityName())
        return key
    }

    /** Returns true if create handled, false if not; if false caller should handle the operation */
    boolean create(EntityValueBase evb) {
        Map key = makeKey(evb)
        if (key == null) return false

        // if create info already exists blow up
        EntityWriteInfo currentEwi = (EntityWriteInfo) writeInfoList.get(key)
        if (readOneCache.get(key) != null)
            throw new EntityException("Tried to create a value that already exists in database, entity [${evb.getEntityName()}], PK ${evb.getPrimaryKeys()}")
        if (currentEwi != null && currentEwi.writeMode != WriteMode.DELETE)
            throw new EntityException("Tried to create a value that already exists in write cache, entity [${evb.getEntityName()}], PK ${evb.getPrimaryKeys()}")

        if (currentEwi != null && currentEwi.writeMode == WriteMode.DELETE) {
            // if deleted remove delete and add an update
            writeInfoList.remove(key)
            writeInfoList.put(key, new EntityWriteInfo(evb, WriteMode.UPDATE))
        } else {
            // add to createCache
            writeInfoList.put(key, new EntityWriteInfo(evb, WriteMode.CREATE))
            getCreateByEntityMap(evb.getEntityName()).put(evb.getPrimaryKeys(), evb)
        }

        // add to readCache after so we don't think it already exists
        readOneCache.put(key, evb)
        // add to any matching list cache entries
        Map<EntityCondition, EntityListImpl> entityListCache = readListCache.get(evb.getEntityName())
        if (entityListCache != null) {
            for (Map.Entry<EntityCondition, EntityListImpl> entry in entityListCache.entrySet()) {
                if (entry.getKey().mapMatches(evb)) entry.getValue().add(evb)
            }
        }

        return true
    }
    boolean update(EntityValueBase evb) {
        Map key = makeKey(evb)
        if (key == null) return false

        // if is in as create or update that value (but stay in current write mode), if delete blow up, otherwise add as update
        EntityWriteInfo currentEwi = (EntityWriteInfo) writeInfoList.get(key)
        if (currentEwi != null) {
            if (currentEwi.writeMode == WriteMode.CREATE || currentEwi.writeMode == WriteMode.UPDATE) {
                // TODO: if new value sets a field with an FK to a field created already in this TX create another
                //  update record (how to do with key strategy!?! maybe add a dummy Map entry...) to avoid FK issues
                currentEwi.evb.setFields(evb, true, null, false)
                evb = currentEwi.evb
            } else {
                throw new EntityException("Tried to update a value that has been deleted, entity [${evb.getEntityName()}], PK ${evb.getPrimaryKeys()}")
            }
        } else {
            if (!evb.getIsFromDb()) {
                EntityValueBase cacheEvb = readOneCache.get(key)
                if (cacheEvb != null) {
                    cacheEvb.setFields(evb, true, null, false)
                    evb = cacheEvb
                } else {
                    EntityValueBase dbEvb = (EntityValueBase) evb.cloneValue()
                    dbEvb.refresh()
                    dbEvb.setFields(evb, true, null, false)
                    evb = dbEvb
                    logger.warn("====== tx cache update not from db\nevb: ${evb}\ndbEvb: ${dbEvb}")
                }
            }

            writeInfoList.put(key, new EntityWriteInfo(evb, WriteMode.UPDATE))
        }

        // add to readCache
        readOneCache.put(key, evb)

        // update any matching list cache entries, add to list cache if not there (though generally should be, depending on the condition)
        Map<EntityCondition, EntityListImpl> entityListCache = readListCache.get(evb.getEntityName())
        if (entityListCache != null) {
            for (Map.Entry<EntityCondition, EntityListImpl> entry in entityListCache.entrySet()) {
                if (entry.getKey().mapMatches(evb)) {
                    // find an existing entry and update it
                    boolean foundEntry = false
                    for (EntityValue existingEv in entry.getValue()) {
                        if (evb.getPrimaryKeys() == existingEv.getPrimaryKeys()) {
                            existingEv.putAll(evb)
                            foundEntry = true
                        }
                    }
                    // if no existing entry found add this
                    if (!foundEntry) entry.getValue().add(evb)
                }
            }
        }

        return true
    }
    boolean delete(EntityValueBase evb) {
        Map key = makeKey(evb)
        if (key == null) return false

        // if in current create remove from write list, if update change to delete, otherwise add as delete
        EntityWriteInfo currentEwi = (EntityWriteInfo) writeInfoList.get(key)
        if (currentEwi != null) {
            if (currentEwi.writeMode == WriteMode.CREATE) {
                writeInfoList.remove(key)
            } else if (currentEwi.writeMode == WriteMode.UPDATE) {
                currentEwi.writeMode = WriteMode.DELETE
            } else {
                // already deleted, throw an exception
                throw new EntityException("Tried to delete a value that already exists, entity [${evb.getEntityName()}], PK ${evb.getPrimaryKeys()}")
            }
        } else {
            writeInfoList.put(key, new EntityWriteInfo(evb, WriteMode.DELETE))
        }

        // remove from readCache if needed
        readOneCache.remove(key)
        // remove any matching list cache entries
        Map<EntityCondition, EntityListImpl> entityListCache = readListCache.get(evb.getEntityName())
        if (entityListCache != null) {
            for (Map.Entry<EntityCondition, EntityListImpl> entry in entityListCache.entrySet()) {
                if (entry.getKey().mapMatches(evb)) {
                    Iterator existingEvIter = entry.getValue().iterator()
                    while (existingEvIter.hasNext()) {
                        EntityValue existingEv = existingEvIter.next()
                        if (evb.getPrimaryKeys() == existingEv.getPrimaryKeys()) existingEvIter.remove()
                    }
                }
            }
        }

        return true
    }
    boolean refresh(EntityValueBase evb) {
        Map key = makeKey(evb)
        if (key == null) return false
        EntityValueBase curEvb = readOneCache.get(key)
        if (curEvb != null) {
            evb.setFields(curEvb, true, null, false)
            return true
        } else {
            return false
        }
    }

    boolean isTxCreate(EntityValueBase evb) {
        Map key = makeKey(evb)
        if (key == null) return false
        return isTxCreate(key)
    }
    /*boolean isTxCreate(String entityName, Map<String, Object> pkMap) {
        Map key = pkMap
        if (!key) return false
        key.put("_entityName", entityName)
        return isTxCreate(key)
    }*/
    protected boolean isTxCreate(Map key) {
        EntityWriteInfo currentEwi = (EntityWriteInfo) writeInfoList.get(key)
        if (currentEwi == null) return false
        return currentEwi.writeMode == WriteMode.CREATE
    }

    EntityValueBase oneGet(EntityFindBase efb) {
        // NOTE: do nothing here on forUpdate, handled by caller
        Map key = makeKeyFind(efb)
        if (key == null) return null

        // if this has been deleted return a DeletedEntityValue instance so caller knows it was deleted and doesn't look in the DB for another record
        EntityWriteInfo currentEwi = (EntityWriteInfo) writeInfoList.get(key)
        if (currentEwi != null && currentEwi.writeMode == WriteMode.DELETE) return new DeletedEntityValue(efb.getEntityDef(), ecfi.getEntityFacade())

        // cloneValue() so that updates aren't in the read cache until an update is done
        return (EntityValueBase) readOneCache.get(key)?.cloneValue()
    }
    void onePut(EntityValueBase evb) {
        Map key = makeKey(evb)
        if (key == null) return
        EntityWriteInfo currentEwi = (EntityWriteInfo) writeInfoList.get(key)
        // if this has been deleted we don't want to add it, but in general if we have a ewi then it's already in the
        //     cache and we don't want to update from this (generally from DB and may be older than value already there)
        // clone the value before putting it into the cache so that the caller can't change it later with an update call
        if (currentEwi == null || currentEwi.writeMode != WriteMode.DELETE) readOneCache.put(key, (EntityValueBase) evb.cloneValue())

        // if (evb.getEntityDefinition().getEntityName() == "Asset") logger.warn("=========== onePut of Asset ${evb.get('assetId')}", new Exception("Location"))
    }

    EntityListImpl listGet(EntityDefinition ed, EntityCondition whereCondition, List<String> orderByExpanded) {
        Map<EntityCondition, EntityListImpl> entityListCache = readListCache.get(ed.getFullEntityName())
        // always clone this so that filters/sorts/etc by callers won't change this
        EntityListImpl cacheList = entityListCache != null ? entityListCache.get(whereCondition)?.deepCloneList() : null

        // if we are searching by a field that is a PK on a related entity to the one being searched it can only exist
        //     in the read cache so find here and don't bother with a DB query
        if (cacheList == null) {
            // if the condition depends on a record that was created in this tx cache, then build the list from here
            //     instead of letting it drop to the DB, finding nothing, then being expanded from the txCache
            Map condMap = [:]
            if (whereCondition != null && whereCondition.populateMap(condMap)) {
                boolean foundCreatedDependent = false

                for (EntityDefinition.RelationshipInfo relInfo in ed.getRelationshipsInfo(false)) {
                    if (relInfo.type != "one") continue
                    // would be nice to skip this, but related-entity-name may not be full entity name
                    EntityDefinition relEd = relInfo.relatedEd
                    String relEntityName = relEd.getFullEntityName()
                    // first see if there is a create Map for this, then do the more expensive operation of getting the
                    //     expanded key Map and the related entity's PK Map
                    Map relCreateMap = getCreateByEntityMap(relEntityName)
                    if (relCreateMap) {
                        Map relKeyMap = relInfo.keyMap
                        Map relPk = [:]
                        boolean foundAllPks = true
                        for (Map.Entry<String, String> entry in relKeyMap.entrySet()) {
                            Object relValue = condMap.get(entry.getKey())
                            if (relValue) relPk.put(entry.getValue(), relValue)
                            else foundAllPks = false
                        }
                        // if (ed.getFullEntityName().contains("OrderItem")) logger.warn("==== listGet ${relEntityName} foundAllPks=${foundAllPks} relPk=${relPk} relCreateMap=${relCreateMap}")
                        if (!foundAllPks) continue
                        if (relCreateMap.containsKey(relPk)) {
                            foundCreatedDependent = true
                            break
                        }
                    }
                }
                if (foundCreatedDependent) {
                    EntityListImpl createdValueList = new EntityListImpl(ecfi.getEntityFacade())
                    Map createMap = createByEntityRef.get(ed.getFullEntityName())
                    if (createMap != null) {
                        for (EntityValueBase createEvb in createMap.values())
                            if (whereCondition.mapMatches(createEvb)) createdValueList.add(createEvb)
                    }

                    listPut(ed, whereCondition, createdValueList)
                    cacheList = createdValueList.deepCloneList()
                }
            }
        }

        if (cacheList && orderByExpanded) cacheList.orderByFields(orderByExpanded)
        return cacheList
    }
    Map<EntityCondition, EntityListImpl> getEntityListCache(String entityName) {
        Map<EntityCondition, EntityListImpl> entityListCache = readListCache.get(entityName)
        if (entityListCache == null) {
            entityListCache = [:]
            readListCache.put(entityName, entityListCache)
        }
        return entityListCache
    }
    void listPut(EntityDefinition ed, EntityCondition whereCondition, EntityListImpl eli) {
        Map<EntityCondition, EntityListImpl> entityListCache = getEntityListCache(ed.getFullEntityName())
        // don't need to do much else here; list will already have values created/updated/deleted in this TX Cache
        entityListCache.put(whereCondition, eli)
    }

    // NOTE: no need to filter EntityList or EntityListIterator, they do it internally by calling this method
    WriteMode checkUpdateValue(EntityValueBase evb) {
        Map key = makeKey(evb)
        if (key == null) return null
        EntityWriteInfo currentEwi = (EntityWriteInfo) writeInfoList.get(key)
        if (currentEwi == null) {
            // add to readCache for future reference
            onePut(evb)
            return null
        }
        if (currentEwi.writeMode == WriteMode.UPDATE) {
            evb.setFields(currentEwi.evb, true, null, false)
            // add to readCache
            onePut(evb)
        } else if (currentEwi.writeMode == WriteMode.CREATE) {
            throw new EntityException("Found value from database that matches a value created in the write-through transaction cache, throwing error now instead of waiting to fail on commit")
        }
        return currentEwi.writeMode
    }
    List<EntityValueBase> getCreatedValueList(String entityName, EntityCondition ec) {
        List<EntityValueBase> valueList = []
        Map<Map, EntityValueBase> createMap = getCreateByEntityMap(entityName)
        if (!createMap) return valueList
        for (EntityValueBase evb in createMap.values()) if (ec.mapMatches(evb)) valueList.add(evb)
        return valueList
    }

    void flushCache() {
        Map<String, Connection> connectionByGroup = [:]
        try {
            EntityFacadeImpl efi = ecfi.getEntityFacade()

            long startTime = System.currentTimeMillis()
            int createCount = 0
            int updateCount = 0
            int deleteCount = 0
            /*for (EntityWriteInfo ewi in (List<EntityWriteInfo>) writeInfoList.valueList()) {
                logger.warn("===== TX Cache value to ${ewi.writeMode} ${ewi.evb.getEntityName()}: \n${ewi.evb}")
            }*/
            for (EntityWriteInfo ewi in (List<EntityWriteInfo>) writeInfoList.valueList()) {
                String groupName = efi.getEntityGroupName(ewi.evb.getEntityName())
                Connection con = connectionByGroup.get(groupName)
                if (con == null) {
                    con = efi.getConnection(groupName)
                    connectionByGroup.put(groupName, con)
                }

                if (ewi.writeMode == WriteMode.CREATE) {
                    ewi.evb.basicCreate(con)
                    createCount++
                } else if (ewi.writeMode == WriteMode.UPDATE) {
                    ewi.evb.basicUpdate(con)
                    updateCount++
                } else {
                    ewi.evb.basicDelete(con)
                    deleteCount++
                }
            }

            if (logger.infoEnabled) logger.info("Wrote from TransactionCache in ${System.currentTimeMillis() - startTime}ms: ${createCount} creates, ${updateCount} updates, ${deleteCount} deletes, ${readOneCache.size()} read entries, ${readListCache.size()} entities with list cache")
        } catch (Throwable t) {
            logger.error("Error writing values from TransactionCache: ${t.toString()}", t)
            throw new XAException("Error writing values from TransactionCache: + ${t.toString()}")
        } finally {
            // now close connections
            for (Connection con in connectionByGroup.values()) con.close()
        }
    }


    @Override
    void beforeCompletion() { flushCache() }

    @Override
    void afterCompletion(int i) { }

    /* Old XAResource approach:

    protected Xid xid = null
    protected Integer timeout = null
    protected boolean active = false
    protected boolean suspended = false

    @Override
    void start(Xid xid, int flag) throws XAException {
        // logger.warn("========== TransactionCache.start(${xid}, ${flag})")
        if (this.active) {
            if (this.xid != null && this.xid.equals(xid)) {
                throw new XAException(XAException.XAER_DUPID);
            } else {
                throw new XAException(XAException.XAER_PROTO);
            }
        }
        if (this.xid != null && !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)

        // logger.warn("================= starting TransactionCache with xid=${xid}; suspended=${suspended}")
        this.active = true
        this.suspended = false
        this.xid = xid
    }

    @Override
    void end(Xid xid, int flag) throws XAException {
        // logger.warn("========== TransactionCache.end(${xid}, ${flag})")
        if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)
        if (flag == TMSUSPEND) {
            if (!this.active) throw new XAException(XAException.XAER_PROTO)
            this.suspended = true
            // logger.warn("================= suspending TransactionCache with xid=${xid}")
        }
        if (flag == TMSUCCESS || flag == TMFAIL) {
            // allow a success/fail end if TX is suspended without a resume flagged start first
            if (!this.active && !this.suspended) throw new XAException(XAException.XAER_PROTO)
        }

        if (flag == TMSUCCESS) {
            // TODO: is this the best event to do this on? need to be able to throw an exception and mark tx for rollback
            // could also do prepare or commit, though found issues on commit when creating tables on the fly
            flushCache()
        }

        this.active = false
    }

    @Override
    void forget(Xid xid) throws XAException {
        // logger.warn("========== TransactionCache.forget(${xid})")
        if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)
        this.xid = null
        if (active) logger.warn("forget() called without end()")
    }

    @Override
    int prepare(Xid xid) throws XAException {
        // logger.warn("========== TransactionCache.prepare(${xid}); this.xid=${this.xid}")
        if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)
        return XA_OK
    }

    @Override
    Xid[] recover(int flag) throws XAException {
        // logger.warn("========== TransactionCache.recover(${flag}); this.xid=${this.xid}")
        return this.xid != null ? [this.xid] : []
    }
    @Override
    boolean isSameRM(XAResource xaResource) throws XAException {
        return xaResource instanceof TransactionCache && ((TransactionCache) xaResource).xid == this.xid
    }
    @Override
    int getTransactionTimeout() throws XAException { return this.timeout == null ? 0 : this.timeout }
    @Override
    boolean setTransactionTimeout(int seconds) throws XAException {
        this.timeout = (seconds == 0 ? null : seconds)
        return true
    }

    @Override
    void commit(Xid xid, boolean onePhase) throws XAException {
        // logger.warn("========== TransactionCache.commit(${xid}, ${onePhase}); this.xid=${this.xid}; this.active=${this.active}")
        if (this.active) logger.warn("commit() called without end()")
        if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)

        // do nothing, moved to end:TMSUCCESS

        this.xid = null
        this.active = false
    }

    @Override
    void rollback(Xid xid) throws XAException {
        // logger.warn("========== TransactionCache.rollback(${xid}); this.xid=${this.xid}; this.active=${this.active}")
        if (this.active) logger.warn("rollback() called without end()")
        if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)

        // do nothing on rollback but maintain state

        this.xid = null
        this.active = false
    }
    */

    static class EntityWriteInfo {
        WriteMode writeMode
        EntityValueBase evb
        Map pkMap
        EntityWriteInfo(EntityValueBase evb, WriteMode writeMode) {
            this.evb = evb
            this.writeMode = writeMode
            this.pkMap = evb.getPrimaryKeys()
        }
    }

    static class DeletedEntityValue extends EntityValueImpl {
        DeletedEntityValue(EntityDefinition ed, EntityFacadeImpl efip) { super(ed, efip) }
    }
}
