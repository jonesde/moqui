/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */
package org.moqui.impl.entity

import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityException
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextFactoryImpl

import javax.transaction.Status
import javax.transaction.Transaction
import javax.transaction.TransactionManager
import javax.transaction.xa.XAException
import javax.transaction.xa.XAResource
import javax.transaction.xa.Xid
import java.sql.Timestamp

class EntityDataFeed {
    protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(EntityDataFeed.class)

    protected EntityFacadeImpl efi

    EntityDataFeed(EntityFacadeImpl efi) {
        this.efi = efi
    }

    EntityFacadeImpl getEfi() { return efi }

    /* Notes:
    - doing update on entity have entityNames updated, for each fieldNames updated, field values (query as needed based
        on actual conditions if any conditions on fields not present in EntityValue
    - do this based on a committed transaction of changes, not just on a single record... if possible
    - if can't do on transaction commit, keep data for documents to include until transaction committed
    - either way only do this on a successful commit, not just on the create/update calls

    to quickly lookup DataDocuments updated with a corresponding real time (DTFDTP_RT_PUSH) DataFeed need:
    - don't have to constrain by real time DataFeed, will be done in advance for index
    - Map with entityName as key
    - value is List of Map with:
      - dataFeedId
      - List of dataDocumentInfo Maps with
        - dataDocumentId
        - Set of fields for DataDocument and the current entity
        - primaryEntityName
        - relationship path from primary to current entity
        - Map of field conditions for current entity - and for entire document? TODO
    - find with query on DataFeed and DataFeedDocument where DataFeed.dataFeedTypeEnumId=DTFDTP_RT_PUSH
      - iterate through dataDocumentId and call getDataDocumentEntityInfo() for each

    to produce the document with zero or minimal query
    - during transaction save all created or updated records in EntityList updatedList (in EntityFacadeImpl?)
    - EntityValues added to the list only if they are in the

    - once we have dataDocumentIdSet use to lookup all DTFDTP_RT_PUSH DataFeed with a matching DataFeedDocument record
    - look up primary entity value for the current updated value and use its PK fields as a condition to call
        getDataDocuments() so that we get a document for just the updated record(s)

     */

    void dataFeedCheckAndRegister(EntityValue ev) {
        // see if this should be added to the feed
        List<DocumentEntityInfo> entityInfoList = getDataFeedEntityInfoList(ev.getEntityName())
        if (entityInfoList) {
            // populate and pass the dataDocumentIdSet, and/or other things needed?
            Set<String> dataDocumentIdSet = new HashSet<String>()
            for (DocumentEntityInfo entityInfo in entityInfoList) {
                // only add dataDocumentId if there are no conditions or if this record matches all conditions (not necessary, but this is an optimization to avoid false positives)
                boolean matchedConditions = true
                if (entityInfo.conditions) for (Map.Entry<String, String> conditionEntry in entityInfo.conditions.entrySet()) {
                    Object evValue = ev.get(conditionEntry.getKey())
                    // if ev doesn't have field populated, ignore the condition; we'll pick it up later in the big document query
                    if (evValue == null) continue
                    if (evValue != conditionEntry.getValue()) { matchedConditions = false; break }
                }
                if (matchedConditions) dataDocumentIdSet.add((String) entityInfo.dataDocumentId)
            }
            if (dataDocumentIdSet) getDataFeedXaResource().addValueToFeed(ev, dataDocumentIdSet)
        }
    }

    DataFeedXaResource getDataFeedXaResource() {
        DataFeedXaResource dfxr = (DataFeedXaResource) efi.getEcfi().getTransactionFacade().getActiveXaResource("DataFeedXaResource")
        if (dfxr == null) {
            dfxr = new DataFeedXaResource(this)
            dfxr.enlist()
        }
        return dfxr
    }

    List<DocumentEntityInfo> getDataFeedEntityInfoList(String fullEntityName) {
        List<DocumentEntityInfo> entityInfoList = (List<DocumentEntityInfo>) efi.dataFeedEntityInfo.get(fullEntityName)
        if (entityInfoList == null) {
            // rebuild from the DB for this and other entities, ie have to do it for all DataFeeds and
            //     DataDocuments because we can't query it by entityName
            EntityList dataFeedAndDocumentList = efi.makeFind("moqui.entity.feed.DataFeedAndDocument")
                    .condition("dataFeedTypeEnumId", "DTFDTP_RT_PUSH").useCache(true).list()
            Set<String> fullDataDocumentIdSet = new HashSet<String>()
            for (EntityValue dataFeedAndDocument in dataFeedAndDocumentList)
                fullDataDocumentIdSet.add((String) dataFeedAndDocument.dataDocumentId)

            for (String dataDocumentId in fullDataDocumentIdSet) {
                Map<String, DocumentEntityInfo> entityInfoMap = getDataDocumentEntityInfo(dataDocumentId)
                // got a Map for all entities in the document, now split them by entity and add to master list for the entity
                for (Map.Entry<String, DocumentEntityInfo> entityInfoMapEntry in entityInfoMap.entrySet()) {
                    List<DocumentEntityInfo> newEntityInfoList = (List<DocumentEntityInfo>) efi.dataFeedEntityInfo.get(entityInfoMapEntry.getKey())
                    newEntityInfoList.add(entityInfoMapEntry.getValue())
                }
            }

            // now we should have all document entityInfos for all entities
            logger.warn("============ efi.dataFeedEntityInfo: ${efi.dataFeedEntityInfo}")

            entityInfoList = (List<DocumentEntityInfo>) efi.dataFeedEntityInfo.get(fullEntityName)
        }
        return entityInfoList
    }

    Map<String, DocumentEntityInfo> getDataDocumentEntityInfo(String dataDocumentId) {
        EntityValue dataDocument = efi.makeFind("moqui.entity.document.DataDocument")
                .condition("dataDocumentId", dataDocumentId).useCache(true).one()
        if (dataDocument == null) throw new EntityException("No DataDocument found with ID [${dataDocumentId}]")
        EntityList dataDocumentFieldList = dataDocument.findRelated("moqui.entity.document.DataDocumentField", null, null, true, false)
        EntityList dataDocumentConditionList = dataDocument.findRelated("moqui.entity.document.DataDocumentCondition", null, null, true, false)

        String primaryEntityName = dataDocument.primaryEntityName
        EntityDefinition primaryEd = efi.getEntityDefinition(primaryEntityName)

        Map<String, DocumentEntityInfo> entityInfoMap = [:]

        // start with the primary entity
        entityInfoMap.put(primaryEntityName, new DocumentEntityInfo(primaryEntityName, dataDocumentId, primaryEntityName, ""))

        // have to go through entire fieldTree instead of entity names directly from fieldPath because may not have hash (#) separator
        Map<String, Object> fieldTree = [:]
        for (EntityValue dataDocumentField in dataDocumentFieldList) {
            String fieldPath = dataDocumentField.fieldPath
            Iterator<String> fieldPathElementIter = fieldPath.split(":").iterator()
            Map currentTree = fieldTree
            DocumentEntityInfo currentEntityInfo = entityInfoMap.get(primaryEntityName)
            StringBuilder currentRelationshipPath = new StringBuilder()
            EntityDefinition currentEd = primaryEd
            while (fieldPathElementIter.hasNext()) {
                String fieldPathElement = fieldPathElementIter.next()
                if (fieldPathElementIter.hasNext()) {
                    if (currentRelationshipPath.length() > 0) currentRelationshipPath.append(":")
                    currentRelationshipPath.append(fieldPathElement)

                    Map subTree = (Map) currentTree.get(fieldPathElement)
                    if (subTree == null) { subTree = [:]; currentTree.put(fieldPathElement, subTree) }
                    currentTree = subTree

                    // make sure we have an entityInfo Map
                    Node relNode = currentEd.getRelationshipNode(fieldPathElement)
                    if (relNode == null) throw new EntityException("Could not find relationship [${fieldPathElement}] from entity [${currentEd.getFullEntityName()}] as part of DataDocumentField.fieldPath [${fieldPath}]")
                    String relEntityName = relNode."@related-entity-name"
                    if (!entityInfoMap.containsKey(relEntityName)) entityInfoMap.put(relEntityName,
                            new DocumentEntityInfo(relEntityName, dataDocumentId, primaryEntityName,
                                    currentRelationshipPath.toString()))
                    currentEntityInfo = entityInfoMap.get(relEntityName)
                    currentEd = efi.getEntityDefinition(relEntityName)
                } else {
                    currentTree.put(fieldPathElement, dataDocumentField.fieldNameAlias ?: fieldPathElement)
                    // save the current field name (not the alias)
                    currentEntityInfo.fields.add(fieldPathElement)
                    // see if there are any conditions for this alias, if so add the fieldName/value to the entity conditions Map
                    for (EntityValue dataDocumentCondition in dataDocumentConditionList) {
                        if (dataDocumentCondition.fieldNameAlias == dataDocumentField.fieldNameAlias)
                            currentEntityInfo.conditions.put(fieldPathElement, (String) dataDocumentCondition.fieldValue)
                    }
                }
            }
        }
        logger.warn("============ got entityInfoMap: ${entityInfoMap}\n============ for fieldTree: ${fieldTree}")

        return entityInfoMap
    }

    static class DocumentEntityInfo implements Serializable {
        String fullEntityName
        String dataDocumentId
        String primaryEntityName
        String relationshipPath
        Set<String> fields = new HashSet<String>()
        Map<String, String> conditions = [:]
        // will we need this? Map<String, DocumentEntityInfo> subEntities

        DocumentEntityInfo(String fullEntityName, String dataDocumentId, String primaryEntityName, String relationshipPath) {
            this.fullEntityName = fullEntityName
            this.dataDocumentId = dataDocumentId
            this.primaryEntityName = primaryEntityName
            this.relationshipPath = relationshipPath
        }

        @Override
        String toString() {
            StringBuilder sb = new StringBuilder()
            sb.append("DocumentEntityInfo [")
            sb.append("fullEntityName:").append(fullEntityName).append(",")
            sb.append("dataDocumentId:").append(dataDocumentId).append(",")
            sb.append("primaryEntityName:").append(primaryEntityName).append(",")
            sb.append("relationshipPath:").append(relationshipPath).append(",")
            sb.append("fields:").append(fields).append(",")
            sb.append("conditions:").append(conditions).append(",")
            sb.append("]")
            return sb.toString()
        }
    }

    static class DataFeedXaResource implements XAResource {
        protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(DataFeedXaResource.class)

        protected ExecutionContextFactoryImpl ecfi
        protected EntityDataFeed edf

        protected Transaction tx = null
        protected Xid xid = null
        protected Integer timeout = null
        protected boolean active = false

        protected EntityList feedValues
        protected Set<String> allDataDocumentIds = new HashSet<String>()

        DataFeedXaResource(EntityDataFeed edf) {
            this.edf = edf
            ecfi = edf.getEfi().getEcfi()
            feedValues = new EntityListImpl(edf.getEfi())
        }

        void enlist() {
            TransactionManager tm = ecfi.getTransactionFacade().getTransactionManager()
            if (tm == null && tm.getStatus() != Status.STATUS_ACTIVE) throw new XAException("Cannot enlist: no transaction manager or transaction not active")
            Transaction tx = tm.getTransaction()
            if (tx == null) throw new XAException(XAException.XAER_NOTA)
            this.tx = tx

            ecfi.getTransactionFacade().putAndEnlistActiveXaResource("DataFeedXaResource", this)
        }

        void addValueToFeed(EntityValue ev, Set<String> dataDocumentIdSet) {
            feedValues.add(ev)
            allDataDocumentIds.addAll(dataDocumentIdSet)
        }

        @Override
        void start(Xid xid, int flag) throws XAException {
            if (this.active) {
                if (this.xid != null && this.xid.equals(xid)) {
                    throw new XAException(XAException.XAER_DUPID);
                } else {
                    throw new XAException(XAException.XAER_PROTO);
                }
            }
            if (this.xid != null && !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)

            this.active = true
            this.xid = xid
        }

        @Override
        void end(Xid xid, int flag) throws XAException {
            if (!this.active) throw new XAException(XAException.XAER_PROTO)
            if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)
            this.active = false
        }

        @Override
        void forget(Xid xid) throws XAException {
            if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)
            this.xid = null
            if (active) logger.warn("forget() called without end()")
        }

        @Override
        int prepare(Xid xid) throws XAException {
            if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)
            return XA_OK
        }

        @Override
        Xid[] recover(int flag) throws XAException { return this.xid != null ? [this.xid] : [] }
        @Override
        boolean isSameRM(XAResource xaResource) throws XAException { return xaResource == this }
        @Override
        int getTransactionTimeout() throws XAException { return this.timeout == null ? 0 : this.timeout }
        @Override
        boolean setTransactionTimeout(int seconds) throws XAException {
            this.timeout = (seconds == 0 ? null : seconds)
            return true
        }

        @Override
        void commit(Xid xid, boolean onePhase) throws XAException {
            if (this.active) logger.warn("commit() called without end()")
            if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)

            // send feed in new thread and tx
            feedInThreadAndTx()

            this.xid = null
            this.active = false
        }

        @Override
        void rollback(Xid xid) throws XAException {
            if (this.active) logger.warn("rollback() called without end()")
            if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)

            // do nothing on rollback but maintain state

            this.xid = null
            this.active = false
        }

        void feedInThreadAndTx() {
            Thread thread = new Thread() {
                @Override
                public void run() {
                    boolean beganTransaction = ecfi.transactionFacade.begin(null)
                    try {
                        EntityFacadeImpl efi = edf.getEfi()
                        Timestamp feedStamp = new Timestamp(System.currentTimeMillis())
                        // assemble data and call DataFeed services

                        // iterate through dataDocumentIdSet
                        for (String dataDocumentId in allDataDocumentIds) {
                            // for each DataDocument go through feedValues and get the primary entity's PK field(s) for each
                            EntityValue dataDocument = efi.makeFind("moqui.entity.document.DataDocument")
                                    .condition("dataDocumentId", dataDocumentId).useCache(true).one()

                            String primaryEntityName = dataDocument.primaryEntityName
                            EntityDefinition primaryEd = efi.getEntityDefinition(primaryEntityName)
                            List<String> primaryPkFieldNames = primaryEd.getPkFieldNames()
                            Set<Map> primaryPkFieldValues = new HashSet<Map>()

                            EntityList dataDocumentFieldList =
                                dataDocument.findRelated("moqui.entity.document.DataDocumentField", null, null, true, false)
                            Map<String, String> pkFieldAliasMap = [:]
                            for (String pkFieldName in primaryPkFieldNames) {
                                boolean aliasSet = false
                                for (EntityValue dataDocumentField in dataDocumentFieldList) {
                                    if (dataDocumentField.fieldPath == pkFieldName) {
                                        pkFieldAliasMap.put(pkFieldName, (String) dataDocumentField.fieldNameAlias ?: pkFieldName)
                                        aliasSet = true
                                    }
                                }
                                if (aliasSet) pkFieldAliasMap.put(pkFieldName, pkFieldName)
                            }


                            for (EntityValue currentEv in feedValues) {
                                String currentEntityName = currentEv.getEntityName()
                                List<DocumentEntityInfo> currentEntityInfoList = edf.getDataFeedEntityInfoList(currentEntityName)
                                for (DocumentEntityInfo currentEntityInfo in currentEntityInfoList) {
                                    if (currentEntityInfo.dataDocumentId == dataDocumentId) {
                                        if (currentEntityName == primaryEntityName) {
                                            // this is the easy one, primary entity updated just use it's values
                                            Map pkFieldValue = [:]
                                            for (String pkFieldName in primaryPkFieldNames)
                                                pkFieldValue.put(pkFieldName, currentEv.get(pkFieldName))
                                            primaryPkFieldValues.add(pkFieldValue)
                                        } else {
                                            // TODO more complex, need to follow relationships backwards (reverse
                                            //     relationships) to get the primary entity's value
                                            List<String> relationshipList = currentEntityInfo.relationshipPath.split(":")

                                        }
                                    }
                                }
                            }

                            // this shouldn't happen, but just in case there aren't really any values for the document
                            //    then skip it, don't want to query with no constraints and get a huge document
                            if (!primaryPkFieldValues) continue

                            // for primary entity with 1 PK field do an IN condition, for >1 PK field do an and cond for
                            //     each PK and an or list cond to combine them
                            EntityCondition condition
                            if (primaryPkFieldNames.size() == 1) {
                                String pkFieldName = primaryPkFieldNames.get(0)
                                Set<Object> pkValues = new HashSet<Object>()
                                for (Map pkFieldValueMap in primaryPkFieldValues)
                                    pkValues.add(pkFieldValueMap.get(pkFieldName))
                                // if pk field is aliased used the alias name
                                condition = efi.getConditionFactory().makeCondition(pkFieldAliasMap.get(pkFieldName),
                                        EntityCondition.IN, pkValues)
                            } else {
                                List<EntityCondition> condList = []
                                for (Map pkFieldValueMap in primaryPkFieldValues) {
                                    Map condAndMap = [:]
                                    // if pk field is aliased used the alias name
                                    for (String pkFieldName in primaryPkFieldNames)
                                        condAndMap.put(pkFieldAliasMap.get(pkFieldName), pkFieldValueMap.get(pkFieldName))
                                    condList.add(efi.getConditionFactory().makeCondition(condAndMap))
                                }
                                condition = efi.getConditionFactory().makeCondition(condList, EntityCondition.OR)
                            }

                            // generate the document with the extra condition and send it to all DataFeeds
                            //     associated with the DataDocument
                            List<Map> documents = efi.getDataDocuments(dataDocumentId, condition, null, null)

                            EntityList dataFeedAndDocumentList = efi.makeFind("moqui.entity.feed.DataFeedAndDocument")
                                    .condition("dataFeedTypeEnumId", "DTFDTP_RT_PUSH")
                                    .condition("dataDocumentId", dataDocumentId).useCache(true).list()
                            for (EntityValue dataFeedAndDocument in dataFeedAndDocumentList) {
                                ecfi.getServiceFacade().async().name((String) dataFeedAndDocument.feedReceiveServiceName)
                                        .parameters([dataFeedId:dataFeedAndDocument.dataFeedId, feedStamp:feedStamp,
                                            documentList:documents]).call()
                            }
                        }
                    } catch (Throwable t) {
                        logger.error("Error running Real-time DataFeed", t)
                        ecfi.transactionFacade.rollback(beganTransaction, "Error running Real-time DataFeed", t)
                    } finally {
                        if (beganTransaction && ecfi.transactionFacade.isTransactionInPlace())
                            ecfi.transactionFacade.commit()
                    }
                }
            };
            thread.start();
        }
    }
}
