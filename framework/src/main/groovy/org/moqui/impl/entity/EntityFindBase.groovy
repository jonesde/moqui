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

import java.sql.ResultSet
import java.sql.Timestamp

import org.apache.commons.collections.set.ListOrderedSet

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityDynamicView
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityException
import org.moqui.entity.EntityList
import org.moqui.entity.EntityListIterator
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.context.CacheImpl
import org.moqui.impl.entity.condition.EntityConditionImplBase
import org.moqui.impl.entity.condition.ListCondition

import net.sf.ehcache.Element

import org.slf4j.Logger
import org.slf4j.LoggerFactory

abstract class EntityFindBase implements EntityFind {
    protected final static Logger logger = LoggerFactory.getLogger(EntityFindBase.class)

    protected final EntityFacadeImpl efi

    protected String entityName
    protected EntityDefinition entityDef = null
    protected EntityDynamicViewImpl dynamicView = null

    protected Map<String, Object> simpleAndMap = null
    protected EntityConditionImplBase whereEntityCondition = null
    protected EntityConditionImplBase havingEntityCondition = null

    /** This is always a ListOrderedSet so that we can get the results in a consistent order */
    protected ListOrderedSet fieldsToSelect = null
    protected List<String> orderByFields = null

    protected Boolean useCache = null

    protected boolean distinct = false
    protected Integer offset = null
    protected Integer limit = null
    protected boolean forUpdate = false

    protected int resultSetType = ResultSet.TYPE_SCROLL_INSENSITIVE
    protected int resultSetConcurrency = ResultSet.CONCUR_READ_ONLY
    protected Integer fetchSize = null
    protected Integer maxRows = null

    EntityFindBase(EntityFacadeImpl efi, String entityName) {
        this.efi = efi
        this.entityName = entityName
    }

    EntityFacadeImpl getEfi() { return efi }

    @Override
    EntityFind entity(String entityName) { this.entityName = entityName; return this }

    @Override
    String getEntity() { return this.entityName }

    // ======================== Conditions (Where and Having) =================

    @Override
    EntityFind condition(String fieldName, Object value) {
        if (!this.simpleAndMap) this.simpleAndMap = new HashMap()
        this.simpleAndMap.put(fieldName, value)
        return this
    }

    @Override
    EntityFind condition(String fieldName, EntityCondition.ComparisonOperator operator, Object value) {
        condition(efi.conditionFactory.makeCondition(fieldName, operator, value))
        return this
    }

    @Override
    EntityFind conditionToField(String fieldName, EntityCondition.ComparisonOperator operator, String toFieldName) {
        condition(efi.conditionFactory.makeCondition(fieldName, operator, toFieldName))
        return this
    }

    @Override
    EntityFind condition(Map<String, ?> fields) {
        if (!fields) return this
        if (!this.simpleAndMap) this.simpleAndMap = new HashMap()
        getEntityDef().setFields(fields, this.simpleAndMap, true, null, null)
        return this
    }

    @Override
    EntityFind condition(EntityCondition condition) {
        if (!condition) return this
        if (whereEntityCondition) {
            // use ListCondition instead of ANDing two at a time to avoid a bunch of nested ANDs
            if (whereEntityCondition instanceof ListCondition) {
                ((ListCondition) whereEntityCondition).addCondition((EntityConditionImplBase) condition)
            } else {
                whereEntityCondition =
                    (EntityConditionImplBase) efi.conditionFactory.makeCondition([whereEntityCondition, condition])
            }
        } else {
            whereEntityCondition = (EntityConditionImplBase) condition
        }
        return this
    }

    @Override
    EntityFind conditionDate(String fromFieldName, String thruFieldName, Timestamp compareStamp) {
        condition(efi.conditionFactory.makeConditionDate(fromFieldName, thruFieldName, compareStamp))
        return this
    }

    @Override
    EntityFind havingCondition(EntityCondition condition) {
        if (!condition) return this
        if (havingEntityCondition) {
            // use ListCondition instead of ANDing two at a time to avoid a bunch of nested ANDs
            if (havingEntityCondition instanceof ListCondition) {
                ((ListCondition) havingEntityCondition).addCondition((EntityConditionImplBase) condition)
            } else {
                havingEntityCondition =
                    (EntityConditionImplBase) efi.conditionFactory.makeCondition([havingEntityCondition, condition])
            }
        } else {
            havingEntityCondition = (EntityConditionImplBase) condition
        }
        return this
    }

    @Override
    EntityCondition getWhereEntityCondition() {
        if (this.simpleAndMap) {
            EntityCondition simpleAndMapCond = this.efi.conditionFactory.makeCondition(this.simpleAndMap)
            if (this.whereEntityCondition) {
                return this.efi.conditionFactory.makeCondition(simpleAndMapCond, EntityCondition.JoinOperator.AND, this.whereEntityCondition)
            } else {
                return simpleAndMapCond
            }
        } else {
            return this.whereEntityCondition
        }
    }

    @Override
    EntityCondition getHavingEntityCondition() {
        return this.havingEntityCondition
    }

    @Override
    EntityFind searchFormInputs(String inputFieldsMapName, String defaultOrderBy, boolean alwaysPaginate) {
        Map inf = inputFieldsMapName ? (Map) efi.ecfi.executionContext.context[inputFieldsMapName] :
            (efi.ecfi.executionContext.web ? efi.ecfi.executionContext.web.parameters : efi.ecfi.executionContext.context)
        EntityDefinition ed = getEntityDef()

        for (String fn in ed.getAllFieldNames()) {
            // NOTE: do we need to do type conversion here?

            // this will handle text-find
            if (inf.containsKey(fn) || inf.containsKey(fn + "_op")) {
                Object value = inf.get(fn)
                String op = inf.get(fn + "_op") ?: "contains"
                boolean not = (inf.get(fn + "_not") == "Y")
                boolean ic = (inf.get(fn + "_ic") == "Y")

                EntityCondition ec = null
                switch (op) {
                    case "equals":
                        if (value) {
                            ec = efi.conditionFactory.makeCondition(fn,
                                    not ? EntityCondition.NOT_EQUAL : EntityCondition.EQUALS, value)
                            if (ic) ec.ignoreCase()
                        }
                        break;
                    case "like":
                        if (value) {
                            ec = efi.conditionFactory.makeCondition(fn,
                                    not ? EntityCondition.NOT_LIKE : EntityCondition.LIKE, value)
                            if (ic) ec.ignoreCase()
                        }
                        break;
                    case "contains":
                        if (value) {
                            ec = efi.conditionFactory.makeCondition(fn,
                                    not ? EntityCondition.NOT_LIKE : EntityCondition.LIKE, "%${value}%")
                            if (ic) ec.ignoreCase()
                        }
                        break;
                    case "empty":
                        ec = efi.conditionFactory.makeCondition(
                                efi.conditionFactory.makeCondition(fn,
                                        not ? EntityCondition.NOT_EQUAL : EntityCondition.EQUALS, null),
                                not ? EntityCondition.JoinOperator.AND : EntityCondition.JoinOperator.OR,
                                efi.conditionFactory.makeCondition(fn,
                                        not ? EntityCondition.NOT_EQUAL : EntityCondition.EQUALS, ""))
                        break;
                }
                if (ec != null) this.condition(ec)
            } else {
                // these will handle range-find and date-find
                if (inf.get(fn + "_from")) this.condition(efi.conditionFactory.makeCondition(fn,
                        EntityCondition.GREATER_THAN_EQUAL_TO, inf.get(fn + "_from")))
                if (inf.get(fn + "_thru")) this.condition(efi.conditionFactory.makeCondition(fn,
                        EntityCondition.LESS_THAN, inf.get(fn + "_thru")))
            }
        }

        // always look for an orderByField parameter too
        String orderByString = inf.get("orderByField") ?: defaultOrderBy
        this.orderBy(orderByString)

        // look for the pageIndex and optional pageSize parameters
        if (alwaysPaginate || inf.get("pageIndex")) {
            int pageIndex = (inf.get("pageIndex") ?: 0) as int
            int pageSize = (inf.get("pageSize") ?: (this.limit ?: 20)) as int
            offset(pageIndex * pageSize)
            limit(pageSize)
        }

        // if there is a pageNoLimit clear out the limit regardless of other settings
        if (inf.get("pageNoLimit") == "true") {
            this.offset = null
            this.limit = null
        }

        return this
    }

    int getPageIndex() { return offset == null ? 0 : offset/getPageSize() }
    int getPageSize() { return limit ?: 20 }

    EntityFind findNode(Node node) {
        ExecutionContext ec = this.efi.ecfi.executionContext

        this.entity((String) node["@entity-name"])
        if (node["@cache"]) { this.useCache(node["@cache"] == "true") }
        if (node["@for-update"]) this.forUpdate(node["@for-update"] == "true")
        if (node["@distinct"]) this.distinct(node["@distinct"] == "true")
        if (node["@offset"]) this.offset(node["@offset"] as Integer)
        if (node["@limit"]) this.limit(node["@limit"] as Integer)
        for (Node sf in (Collection<Node>) node["select-field"]) this.selectField((String) sf["@field-name"])
        for (Node ob in (Collection<Node>) node["order-by"]) this.orderBy((String) ob["@field-name"])

        if (!this.getUseCache()) {
            for (Node df in (Collection<Node>) node["date-filter"])
                this.condition(ec.entity.conditionFactory.makeConditionDate((String) df["@from-field-name"] ?: "fromDate",
                        (String) df["@thru-field-name"] ?: "thruDate",
                        (df["@valid-date"] ? ec.resource.evaluateContextField((String) df["@valid-date"], null) as Timestamp : ec.user.nowTimestamp)))
        }

        for (Node ecn in (Collection<Node>) node["econdition"])
            this.condition(((EntityConditionFactoryImpl) efi.conditionFactory).makeActionCondition(ecn))
        for (Node ecs in (Collection<Node>) node["econditions"])
            this.condition(((EntityConditionFactoryImpl) efi.conditionFactory).makeActionConditions(ecs))
        for (Node eco in (Collection<Node>) node["econdition-object"])
            this.condition((EntityCondition) ec.resource.evaluateContextField((String) eco["@field"], null))

        if (node["search-form-inputs"]) {
            Node sfiNode = (Node) node["search-form-inputs"].getAt(0)
            searchFormInputs((String) sfiNode["@input-fields-map"], (String) sfiNode["@default-order-by"], (sfiNode["@paginate"] ?: "true") as boolean)
        }
        if (node["having-econditions"]) {
            for (Node havingCond in (Collection<Node>) node["having-econditions"])
                this.havingCondition(((EntityConditionFactoryImpl) efi.conditionFactory).makeActionCondition(havingCond))
        }

        // logger.info("TOREMOVE Added findNode\n${node}\n${this.toString()}")

        return this
    }

    // ======================== General/Common Options ========================

    @Override
    EntityFind selectField(String fieldToSelect) {
        if (!this.fieldsToSelect) this.fieldsToSelect = new ListOrderedSet()
        if (fieldToSelect) this.fieldsToSelect.add(fieldToSelect)
        return this
    }

    @Override
    EntityFind selectFields(Collection<String> fieldsToSelect) {
        if (!this.fieldsToSelect) this.fieldsToSelect = new ListOrderedSet()
        if (fieldsToSelect) this.fieldsToSelect.addAll(fieldsToSelect)
        return this
    }

    @Override
    List<String> getSelectFields() { return this.fieldsToSelect ? this.fieldsToSelect.asList() : null }

    @Override
    EntityFind orderBy(String orderByFieldName) {
        if (!this.orderByFields) this.orderByFields = new ArrayList()
        if (orderByFieldName) {
            if (orderByFieldName.contains(",")) {
                for (String obsPart in orderByFieldName.split(",")) this.orderByFields.add(obsPart.trim())
            } else {
                this.orderByFields.add(orderByFieldName)
            }
        }
        return this
    }

    @Override
    EntityFind orderBy(List<String> orderByFieldNames) {
        if (!this.orderByFields) this.orderByFields = new ArrayList()
        if (orderByFieldNames) this.orderByFields.addAll(orderByFieldNames)
        return this
    }

    @Override
    List<String> getOrderBy() { return this.orderByFields ? Collections.unmodifiableList(this.orderByFields) : null }

    @Override
    EntityFind useCache(Boolean useCache) { this.useCache = useCache; return this }

    @Override
    boolean getUseCache() { return this.useCache }

    // ======================== Advanced Options ==============================

    @Override
    EntityFind distinct(boolean distinct) { this.distinct = distinct; return this }
    @Override
    boolean getDistinct() { return this.distinct }

    @Override
    EntityFind offset(Integer offset) { this.offset = offset; return this }
    @Override
    EntityFind offset(int pageIndex, int pageSize) { offset(pageIndex * pageSize) }
    @Override
    Integer getOffset() { return this.offset }

    @Override
    EntityFind limit(Integer limit) { this.limit = limit; return this }
    @Override
    Integer getLimit() { return this.limit }

    @Override
    EntityFind forUpdate(boolean forUpdate) { this.forUpdate = forUpdate; return this }
    @Override
    boolean getForUpdate() { return this.forUpdate }

    // ======================== JDBC Options ==============================

    @Override
    EntityFind resultSetType(int resultSetType) { this.resultSetType = resultSetType; return this }
    @Override
    int getResultSetType() { return this.resultSetType }

    @Override
    EntityFind resultSetConcurrency(int resultSetConcurrency) {
        this.resultSetConcurrency = resultSetConcurrency
        return this
    }
    @Override
    int getResultSetConcurrency() { return this.resultSetConcurrency }

    @Override
    EntityFind fetchSize(Integer fetchSize) { this.fetchSize = fetchSize; return this }
    @Override
    Integer getFetchSize() { return this.fetchSize }

    @Override
    EntityFind maxRows(Integer maxRows) { this.maxRows = maxRows; return this }
    @Override
    Integer getMaxRows() { return this.maxRows }

    // ======================== Misc Methods ========================
    protected EntityDefinition getEntityDef() {
        if (this.entityDef) return this.entityDef
        if (this.dynamicView) {
            this.entityDef = this.dynamicView.makeEntityDefinition()
        } else {
            this.entityDef = this.efi.getEntityDefinition(this.entityName)
        }
        return this.entityDef
    }

    protected boolean shouldCache() {
        if (this.dynamicView) return false
        String entityCache = this.getEntityDef().getEntityNode()."@use-cache"
        return ((this.useCache == Boolean.TRUE && entityCache != "never") || entityCache == "true")
    }

    @Override
    String toString() {
        return "Find: ${entityName} WHERE [${simpleAndMap}] [${whereEntityCondition}] HAVING [${havingEntityCondition}] " +
                "SELECT [${fieldsToSelect}] ORDER BY [${orderByFields}] CACHE [${useCache}] DISTINCT [${distinct}] " +
                "OFFSET [${offset}] LIMIT [${limit}] FOR UPDATE [${forUpdate}]"
    }

    // ======================== Abstract Methods ========================
    abstract EntityDynamicView makeEntityDynamicView()

    @Override
    EntityValue one() throws EntityException {
        if (this.dynamicView) {
            throw new IllegalArgumentException("Dynamic View not supported for 'one' find.")
        }

        long startTime = System.currentTimeMillis()
        EntityDefinition ed = this.getEntityDef()
        Node entityNode = ed.getEntityNode()
        ExecutionContext ec = efi.ecfi.getExecutionContext()

        if (ed.isViewEntity() && (!entityNode."member-entity" || !entityNode."alias"))
            throw new EntityException("Cannot do find for view-entity with name [${entityName}] because it has no member entities or no aliased fields.")

        ec.getArtifactExecution().push(
                new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW"),
                (entityNode."@authorize-skip" != "true" && !entityNode."@authorize-skip"?.contains("view")))

        efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-one", true)

        // if over-constrained (anything in addition to a full PK), just use the full PK
        EntityConditionImplBase whereCondition
        if (ed.containsPrimaryKey(simpleAndMap)) {
            whereCondition = (EntityConditionImplBase) efi.getConditionFactory().makeCondition(ed.getPrimaryKeys(simpleAndMap))
        } else {
            whereCondition = (EntityConditionImplBase) getWhereEntityCondition()
        }

        // no condition means no condition/parameter set, so return null for find.one()
        if (!whereCondition) {
            // pop the ArtifactExecutionInfo
            ec.artifactExecution.pop()
            return null
        }

        CacheImpl entityOneCache = null
        boolean doCache = this.shouldCache()
        if (doCache) {
            entityOneCache = this.efi.getEntityCache().getCacheOne(this.entityName)
            Element cacheElement = entityOneCache.getElement(whereCondition)
            if (cacheElement != null) {
                if (cacheElement.expired) {
                    entityOneCache.removeElement(cacheElement)
                } else {
                    EntityValue cacheHit = (EntityValue) cacheElement.objectValue
                    // if (logger.traceEnabled) logger.trace("Found entry in cache for entity [${ed.entityName}] and condition [${whereCondition}]: ${cacheHit}")
                    efi.runEecaRules(ed.getFullEntityName(), cacheHit, "find-one", false)
                    // pop the ArtifactExecutionInfo
                    ec.getArtifactExecution().pop()
                    return cacheHit
                }
            }
        }

        // NOTE: do this as a separate condition because this will always be added on and isn't a part of the original where to use for the cache
        EntityConditionImplBase conditionForQuery
        EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
        if (viewWhere) {
            if (whereCondition) {
                conditionForQuery = (EntityConditionImplBase) efi.getConditionFactory().makeCondition(whereCondition,
                        EntityCondition.JoinOperator.AND, viewWhere)
            } else {
                conditionForQuery = viewWhere
            }
        } else {
            conditionForQuery = whereCondition
        }

        // for find one we'll always use the basic result set type and concurrency:
        this.resultSetType(ResultSet.TYPE_FORWARD_ONLY)
        this.resultSetConcurrency(ResultSet.CONCUR_READ_ONLY)

        // we always want fieldsToSelect populated so that we know the order of the results coming back
        if (!this.fieldsToSelect) this.selectFields(ed.getFieldNames(true, true, false))
        // TODO: this will not handle query conditions on UserFields, it will blow up in fact


        // call the abstract method
        EntityValue newEntityValue = oneExtended(conditionForQuery)


        // put it in whether null or not
        if (doCache) {
            entityOneCache.put(whereCondition.toString(), newEntityValue)
            // need to register an RA just in case the condition was not actually a primary key
            efi.getEntityCache().registerCacheOneRa(this.entityName, whereCondition, (EntityValueBase) newEntityValue)
        }

        if (logger.traceEnabled) logger.trace("Find one on entity [${ed.entityName}] with condition [${whereCondition}] found value [${newEntityValue}]")

        // final ECA trigger
        efi.runEecaRules(ed.getFullEntityName(), newEntityValue, "find-one", false)
        // count the artifact hit
        efi.ecfi.countArtifactHit("entity", "one", ed.getEntityName(), simpleAndMap, startTime, System.currentTimeMillis(), newEntityValue ? 1 : 0)
        // pop the ArtifactExecutionInfo
        ec.getArtifactExecution().pop()

        return newEntityValue
    }
    abstract EntityValue oneExtended(EntityConditionImplBase whereCondition) throws EntityException

    @Override
    EntityList list() throws EntityException {
        long startTime = System.currentTimeMillis()
        EntityDefinition ed = this.getEntityDef()
        Node entityNode = ed.getEntityNode()
        ExecutionContext ec = efi.ecfi.executionContext

        if (ed.isViewEntity() && (!entityNode."member-entity" || !entityNode."alias"))
            throw new EntityException("Cannot do find for view-entity with name [${entityName}] because it has no member entities or no aliased fields.")

        ec.getArtifactExecution().push(
                new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW"),
                (entityNode."@authorize-skip" != "true" && !entityNode."@authorize-skip"?.contains("view")))

        // there may not be a simpleAndMap, but that's all we have that can be treated directly by the EECA
        efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-list", true)

        EntityConditionImplBase whereCondition = (EntityConditionImplBase) getWhereEntityCondition()
        CacheImpl entityListCache = null
        // NOTE: don't cache if there is a having condition, for now just support where
        boolean doCache = !this.havingEntityCondition && this.shouldCache()
        if (doCache) {
            entityListCache = this.efi.getEntityCache().getCacheList(this.entityName)
            Element cacheElement = entityListCache.getElement(whereCondition)
            if (cacheElement != null) {
                if (cacheElement.expired) {
                    entityListCache.removeElement(cacheElement)
                } else {
                    EntityList cacheHit = (EntityList) cacheElement.objectValue
                    efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-list", false)
                    // pop the ArtifactExecutionInfo
                    ec.getArtifactExecution().pop()
                    return cacheHit
                }
            }
        }

        // we always want fieldsToSelect populated so that we know the order of the results coming back
        if (!this.fieldsToSelect) this.selectFields(ed.getFieldNames(true, true, false))
        // TODO: this will not handle query conditions on UserFields, it will blow up in fact

        if (ed.isViewEntity() && ed.getEntityNode()."entity-condition"[0]?."@distinct" == "true") this.distinct(true)

        EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
        if (whereCondition && viewWhere) {
            whereCondition =
                (EntityConditionImplBase) efi.getConditionFactory().makeCondition(whereCondition, EntityCondition.JoinOperator.AND, viewWhere)
        } else if (viewWhere) {
            whereCondition = viewWhere
        }
        EntityConditionImplBase havingCondition = (EntityConditionImplBase) getHavingEntityCondition()
        EntityConditionImplBase viewHaving = ed.makeViewHavingCondition()
        if (havingCondition && viewHaving) {
            havingCondition =
                (EntityConditionImplBase) efi.getConditionFactory().makeCondition(havingCondition, EntityCondition.JoinOperator.AND, viewHaving)
        } else if (viewHaving) {
            havingCondition = viewHaving
        }

        List<String> orderByExpanded = new ArrayList()
        // add the manually specified ones, then the ones in the view entity's entity-condition
        if (this.getOrderBy()) orderByExpanded.addAll(this.getOrderBy())
        def ecObList = ed.getEntityNode()."entity-condition"?.getAt(0)?."order-by"
        if (ecObList) for (Node orderBy in ecObList) orderByExpanded.add(orderBy."@field-name")

        // call the abstract method
        EntityListIterator eli = this.iteratorExtended(whereCondition, havingCondition, orderByExpanded)

        EntityListImpl el
        Node databaseNode = this.efi.getDatabaseNode(this.efi.getEntityGroupName(ed))
        if (this.limit != null && databaseNode != null && databaseNode."@offset-style" == "cursor") {
            el = eli.getPartialList(this.offset ?: 0, this.limit, true)
        } else {
            el = eli.getCompleteList(true)
        }

        if (doCache) {
            EntityListImpl elToCache = el ?: EntityListImpl.EMPTY
            elToCache.setFromCache(true)
            entityListCache.put(whereCondition.toString(), elToCache)
            efi.getEntityCache().registerCacheListRa(this.entityName, whereCondition, elToCache)
        }
        // run the final rules
        efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-list", false)
        // count the artifact hit
        efi.ecfi.countArtifactHit("entity", "list", ed.getEntityName(), simpleAndMap, startTime,
                System.currentTimeMillis(), el ? el.size() : 0)
        // pop the ArtifactExecutionInfo
        ec.getArtifactExecution().pop()

        return el
    }

    @Override
    EntityListIterator iterator() throws EntityException {
        long startTime = System.currentTimeMillis()
        EntityDefinition ed = this.getEntityDef()
        Node entityNode = ed.getEntityNode()
        ExecutionContext ec = efi.ecfi.executionContext

        if (ed.isViewEntity() && (!entityNode."member-entity" || !entityNode."alias"))
            throw new EntityException("Cannot do find for view-entity with name [${entityName}] because it has no member entities or no aliased fields.")

        ec.getArtifactExecution().push(
                new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW"),
                (entityNode."@authorize-skip" != "true" && !entityNode."@authorize-skip"?.contains("view")))

        // there may not be a simpleAndMap, but that's all we have that can be treated directly by the EECA
        efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-iterator", true)

        // we always want fieldsToSelect populated so that we know the order of the results coming back
        if (!this.fieldsToSelect) this.selectFields(ed.getFieldNames(true, true, false))
        // TODO: this will not handle query conditions on UserFields, it will blow up in fact

        if (ed.isViewEntity() && ed.getEntityNode()."entity-condition"[0]?."@distinct" == "true") this.distinct(true)

        EntityConditionImplBase whereCondition = (EntityConditionImplBase) getWhereEntityCondition()
        EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
        if (whereCondition && viewWhere) {
            whereCondition = (EntityConditionImplBase) efi.getConditionFactory().makeCondition(whereCondition,
                    EntityCondition.JoinOperator.AND, viewWhere)
        } else if (viewWhere) {
            whereCondition = viewWhere
        }
        EntityConditionImplBase havingCondition = (EntityConditionImplBase) getHavingEntityCondition()
        EntityConditionImplBase viewHaving = ed.makeViewHavingCondition()
        if (havingCondition && viewHaving) {
            havingCondition = (EntityConditionImplBase) efi.getConditionFactory().makeCondition(havingCondition,
                        EntityCondition.JoinOperator.AND, viewHaving)
        } else if (viewHaving) {
            havingCondition = viewHaving
        }

        List<String> orderByExpanded = new ArrayList()
        // add the manually specified ones, then the ones in the view entity's entity-condition
        if (this.getOrderBy()) orderByExpanded.addAll(this.getOrderBy())
        def ecObList = ed.getEntityNode()."entity-condition"?.getAt(0)?."order-by"
        if (ecObList) for (Node orderBy in ecObList) orderByExpanded.add(orderBy."@field-name")

        // call the abstract method
        EntityListIterator eli = iteratorExtended(whereCondition, havingCondition, orderByExpanded)

        // NOTE: if we are doing offset/limit with a cursor no good way to limit results, but we'll at least jump to the offset
        Node databaseNode = this.efi.getDatabaseNode(this.efi.getEntityGroupName(ed))
        if (databaseNode."@offset-style" == "cursor") {
            if (!eli.absolute(offset)) {
                // can't seek to desired offset? not enough results, just go to after last result
                eli.afterLast()
            }
        }

        efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-iterator", false)
        // count the artifact hit
        efi.ecfi.countArtifactHit("entity", "iterator", ed.getEntityName(), simpleAndMap, startTime, System.currentTimeMillis(), null)
        // pop the ArtifactExecutionInfo
        ec.getArtifactExecution().pop()

        return eli
    }

    abstract EntityListIterator iteratorExtended(EntityConditionImplBase whereCondition,
                                                 EntityConditionImplBase havingCondition, List<String> orderByExpanded)

    @Override
    long count() throws EntityException {
        long startTime = System.currentTimeMillis()
        EntityDefinition ed = this.getEntityDef()
        Node entityNode = ed.getEntityNode()
        ExecutionContext ec = efi.ecfi.executionContext

        ec.getArtifactExecution().push(
                new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW"),
                (entityNode."@authorize-skip" != "true" && !entityNode."@authorize-skip"?.contains("view")))

        // there may not be a simpleAndMap, but that's all we have that can be treated directly by the EECA
        efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-count", true)

        EntityConditionImplBase whereCondition = (EntityConditionImplBase) getWhereEntityCondition()
        CacheImpl entityCountCache = null
        // NOTE: don't cache if there is a having condition, for now just support where
        boolean doCache = !this.havingEntityCondition && this.shouldCache()
        if (doCache) {
            entityCountCache = this.efi.getCacheCount(this.entityName)
            Element cacheElement = entityCountCache.getElement(whereCondition)
            if (cacheElement != null) {
                if (cacheElement.expired) {
                    entityCountCache.removeElement(cacheElement)
                } else {
                    Long cacheHit = (Long) cacheElement.objectValue
                    efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-count", false)
                    // pop the ArtifactExecutionInfo
                    ec.getArtifactExecution().pop()
                    return cacheHit
                }
            }
        }

        if (!this.fieldsToSelect) this.selectFields(ed.getFieldNames(false, true, false))
        // TODO: this will not handle query conditions on UserFields, it will blow up in fact

        if (ed.isViewEntity() && ed.getEntityNode()."entity-condition"[0]?."@distinct" == "true") this.distinct(true)

        EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
        if (whereCondition && viewWhere) {
            whereCondition =
                (EntityConditionImplBase) efi.getConditionFactory().makeCondition(whereCondition, EntityCondition.JoinOperator.AND, viewWhere)
        } else if (viewWhere) {
            whereCondition = viewWhere
        }

        EntityConditionImplBase havingCondition = (EntityConditionImplBase) getHavingEntityCondition()
        EntityConditionImplBase viewHaving = ed.makeViewHavingCondition()
        if (havingCondition && viewHaving) {
            havingCondition =
                (EntityConditionImplBase) efi.getConditionFactory().makeCondition(havingCondition, EntityCondition.JoinOperator.AND, viewHaving)
        } else if (viewHaving) {
            havingCondition = viewHaving
        }

        // call the abstract method
        long count = countExtended(whereCondition, havingCondition)

        if (doCache) entityCountCache.put(whereCondition.toString(), count)

        efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-count", false)
        // count the artifact hit
        efi.ecfi.countArtifactHit("entity", "count", ed.getEntityName(), simpleAndMap, startTime, System.currentTimeMillis(), count)
        // pop the ArtifactExecutionInfo
        ec.getArtifactExecution().pop()

        return count
    }
    abstract long countExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition)
            throws EntityException

    @Override
    long updateAll(Map<String, ?> fieldsToSet) {
        // NOTE: this code isn't very efficient, but will do the trick and cause all EECAs to be fired
        // NOTE: consider expanding this to do a bulk update in the DB if there are no EECAs for the entity
        this.useCache(false).forUpdate(true)
        long totalUpdated = 0
        EntityList el = list()
        for (EntityValue value in el) {
            value.putAll(fieldsToSet)
            if (value.isModified()) {
                // NOTE: consider implement and use the eli.set(value) method to update within a ResultSet
                value.update()
                totalUpdated++
            }
        }
        return totalUpdated
    }

    @Override
    long deleteAll() {
        // NOTE: this code isn't very efficient, but will do the trick and cause all EECAs to be fired
        // NOTE: consider expanding this to do a bulk delete in the DB if there are no EECAs for the entity
        this.useCache(false).forUpdate(true)
        this.resultSetConcurrency(ResultSet.CONCUR_UPDATABLE)
        EntityListIterator eli = null
        long totalDeleted = 0
        try {
            eli = iterator()
            while (eli.next() != null) {
                eli.remove()
                totalDeleted++
            }
        } finally {
            if (eli != null) eli.close()
        }
        return totalDeleted
    }
}
