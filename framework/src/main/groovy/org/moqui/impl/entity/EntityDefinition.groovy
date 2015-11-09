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
package org.moqui.impl.entity

import groovy.transform.CompileStatic
import org.apache.commons.codec.binary.Base64
import org.moqui.impl.StupidUtilities

import javax.sql.rowset.serial.SerialBlob
import java.sql.Date
import java.sql.Timestamp

import org.apache.commons.collections.set.ListOrderedSet

import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityCondition.JoinOperator
import org.moqui.entity.EntityException
import org.moqui.impl.entity.condition.EntityConditionImplBase
import org.moqui.impl.entity.condition.ConditionField
import org.moqui.impl.entity.condition.FieldValueCondition
import org.moqui.impl.entity.condition.FieldToFieldCondition
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityList
import org.moqui.BaseException

import org.slf4j.Logger
import org.slf4j.LoggerFactory

public class EntityDefinition {
    protected final static Logger logger = LoggerFactory.getLogger(EntityDefinition.class)

    protected EntityFacadeImpl efi
    protected String internalEntityName
    protected String fullEntityName
    protected String shortAlias
    protected String groupName = null
    protected Node internalEntityNode
    protected final Map<String, Node> fieldNodeMap = new HashMap<String, Node>()
    protected final Map<String, FieldInfo> fieldInfoMap = new HashMap<String, FieldInfo>()
    // small lists, but very frequently accessed
    protected ArrayList<String> pkFieldNameList = null
    protected ArrayList<String> nonPkFieldNameList = null
    protected ArrayList<FieldInfo> nonPkFieldInfoList = null
    protected ArrayList<String> allFieldNameList = null
    protected Boolean hasUserFields = null
    protected Boolean allowUserField = null
    protected Map<String, Map> mePkFieldToAliasNameMapMap = null

    protected Boolean isView = null
    protected Boolean needsAuditLogVal = null
    protected Boolean needsEncryptVal = null
    protected Boolean createOnlyVal = null
    protected Boolean optimisticLockVal = null
    protected String useCache = null
    protected String sequencePrimaryPrefix = ""
    protected long sequencePrimaryStagger = 1
    protected long sequenceBankSize = EntityFacadeImpl.defaultBankSize

    protected List<Node> expandedRelationshipList = null
    // this is kept separately for quick access to relationships by name or short-alias
    protected Map<String, RelationshipInfo> relationshipInfoMap = null
    protected List<RelationshipInfo> relationshipInfoList = null
    protected boolean hasReverseRelationships = false
    protected Map<String, MasterDefinition> masterDefinitionMap = null

    EntityDefinition(EntityFacadeImpl efi, Node entityNode) {
        this.efi = efi
        // copy the entityNode because we may be modifying it
        this.internalEntityNode = StupidUtilities.deepCopyNode(entityNode)
        this.internalEntityName = (internalEntityNode."@entity-name").intern()
        this.fullEntityName = (internalEntityNode."@package-name" + "." + this.internalEntityName).intern()
        this.shortAlias = internalEntityNode."@short-alias" ?: null
        this.sequencePrimaryPrefix = internalEntityNode."@sequence-primary-prefix" ?: ""
        if (internalEntityNode."@sequence-primary-stagger")
            this.sequencePrimaryStagger = internalEntityNode."@sequence-primary-stagger" as long
        if (internalEntityNode."@sequence-bank-size")
            this.sequenceBankSize = internalEntityNode."@sequence-bank-size" as long

        if (isViewEntity()) {
            // get group-name, etc from member-entity
            for (Node memberEntity in internalEntityNode."member-entity") {
                EntityDefinition memberEd = this.efi.getEntityDefinition((String) memberEntity."@entity-name")
                Node memberEntityNode = memberEd.getEntityNode()
                if (memberEntityNode."@group-name") internalEntityNode.attributes().put("group-name", memberEntityNode."@group-name")
            }
            // if this is a view-entity, expand the alias-all elements into alias elements here
            this.expandAliasAlls()
            // set @type, set is-pk on all alias Nodes if the related field is-pk
            for (Node aliasNode in internalEntityNode."alias") {
                Node memberEntity = (Node) internalEntityNode."member-entity".find({ it."@entity-alias" == aliasNode."@entity-alias" })
                if (memberEntity == null) {
                    if (aliasNode."complex-alias") {
                        continue
                    } else {
                        throw new EntityException("Could not find member-entity with entity-alias [${aliasNode."@entity-alias"}] in view-entity [${internalEntityName}]")
                    }
                }
                EntityDefinition memberEd = this.efi.getEntityDefinition((String) memberEntity."@entity-name")
                String fieldName = aliasNode."@field" ?: aliasNode."@name"
                Node fieldNode = memberEd.getFieldNode(fieldName)
                if (fieldNode == null) throw new EntityException("In view-entity [${internalEntityName}] alias [${aliasNode."@name"}] referred to field [${fieldName}] that does not exist on entity [${memberEd.internalEntityName}].")
                if (!aliasNode.attribute("type")) aliasNode.attributes().put("type", fieldNode.attribute("type"))
                if (fieldNode."@is-pk" == "true") aliasNode."@is-pk" = "true"
            }
            for (Node aliasNode in internalEntityNode."alias") {
                String fieldName = (String) aliasNode."@name"
                fieldNodeMap.put(fieldName, aliasNode)
                fieldInfoMap.put(fieldName, new FieldInfo(this, aliasNode))
            }
        } else {
            if (internalEntityNode."@no-update-stamp" != "true") {
                // automatically add the lastUpdatedStamp field
                internalEntityNode.appendNode("field", [name:"lastUpdatedStamp", type:"date-time"])
            }
            if (internalEntityNode."@allow-user-field" == "true") allowUserField = true

            for (Node fieldNode in this.internalEntityNode.field) {
                String fieldName = (String) fieldNode."@name"
                fieldNodeMap.put(fieldName, fieldNode)
                fieldInfoMap.put(fieldName, new FieldInfo(this, fieldNode))
            }
        }
    }

    @CompileStatic
    String getEntityName() { return this.internalEntityName }
    @CompileStatic
    String getFullEntityName() { return this.fullEntityName }
    @CompileStatic
    String getShortAlias() { return this.shortAlias }

    @CompileStatic
    Node getEntityNode() { return this.internalEntityNode }

    @CompileStatic
    boolean isViewEntity() {
        if (isView == null) isView = (this.internalEntityNode.name() == "view-entity")
        return isView
    }
    boolean hasFunctionAlias() { return isViewEntity() && this.internalEntityNode."alias".find({ it."@function" }) }

    String getEntityGroupName() {
        if (groupName == null) {
            if (internalEntityNode."@is-dynamic-view" == "true") {
                // use the name of the first member-entity
                String memberEntityName = internalEntityNode."member-entity".find({ !it."@join-from-alias" })?."@entity-name"
                groupName = efi.getEntityGroupName(memberEntityName)
            } else {
                groupName = internalEntityNode."@group-name" ?: efi.getDefaultGroupName()
            }
        }
        return groupName
    }

    String getDefaultDescriptionField() {
        List<String> nonPkFields = getFieldNames(false, true, false)
        // find the first *Name
        for (String fn in nonPkFields)
            if (fn.endsWith("Name")) return fn

        // no name? try literal description
        if (isField("description")) return "description"

        // no description? just use the first non-pk field: nonPkFields.get(0)
        // not any more, can be confusing... just return empty String
        return ""
    }

    @CompileStatic
    boolean createOnly() {
        if (createOnlyVal != null) return createOnlyVal
        createOnlyVal = "true".equals(internalEntityNode.attribute('create-only'))
        return createOnlyVal
    }

    @CompileStatic
    boolean optimisticLock() {
        if (optimisticLockVal != null) return optimisticLockVal
        optimisticLockVal = "true".equals(internalEntityNode.attribute('optimistic-lock'))
        return optimisticLockVal
    }

    @CompileStatic
    boolean needsAuditLog() {
        if (needsAuditLogVal != null) return needsAuditLogVal
        needsAuditLogVal = false
        for (Node fieldNode in getFieldNodes(true, true, false))
            if (getFieldAuditLog(fieldNode) == "true" || getFieldAuditLog(fieldNode) == "update") needsAuditLogVal = true
        if (needsAuditLogVal) return true

        for (Node fieldNode in getFieldNodes(false, false, true))
            if (getFieldAuditLog(fieldNode) == "true" || getFieldAuditLog(fieldNode) == "update") needsAuditLogVal = true
        return needsAuditLogVal
    }
    @CompileStatic
    String getFieldAuditLog(Node fieldNode) {
        String fieldAuditLog = fieldNode.attribute('enable-audit-log')
        if (fieldAuditLog) return fieldAuditLog
        return internalEntityNode.attribute('enable-audit-log')
    }

    @CompileStatic
    boolean needsEncrypt() {
        if (needsEncryptVal != null) return needsEncryptVal
        needsEncryptVal = false
        for (Node fieldNode in getFieldNodes(true, true, false)) {
            if (fieldNode.attribute('encrypt') == "true") needsEncryptVal = true
        }
        if (needsEncryptVal) return true

        for (Node fieldNode in getFieldNodes(false, false, true)) {
            if (fieldNode.attribute('encrypt') == "true") needsEncryptVal = true
        }

        return needsEncryptVal
    }

    @CompileStatic
    String getUseCache() {
        if (useCache == null) useCache = internalEntityNode.attribute('cache') ?: 'false'
        return useCache
    }

    @CompileStatic
    Node getFieldNode(String fieldName) {
        Node fn = fieldNodeMap.get(fieldName)
        if (fn != null) return fn

        if (allowUserField && !this.isViewEntity() && !fieldName.contains('.')) {
            // if fieldName has a dot it is likely a relationship name, so don't look for UserField

            boolean alreadyDisabled = efi.getEcfi().getExecutionContext().getArtifactExecution().disableAuthz()
            try {
                EntityList userFieldList = efi.find("moqui.entity.UserField").condition("entityName", getFullEntityName()).useCache(true).list()
                if (userFieldList) {
                    Set<String> userGroupIdSet = efi.getEcfi().getExecutionContext().getUser().getUserGroupIdSet()
                    for (EntityValue userField in userFieldList) {
                        if (userField.fieldName != fieldName) continue
                        if (userGroupIdSet.contains(userField.userGroupId)) {
                            fn = makeUserFieldNode(userField)
                            break
                        }
                    }
                }
            } finally {
                if (!alreadyDisabled) efi.getEcfi().getExecutionContext().getArtifactExecution().enableAuthz()
            }
        }

        return fn
    }

    @CompileStatic
    FieldInfo getFieldInfo(String fieldName) {
        FieldInfo fi = fieldInfoMap.get(fieldName)
        if (fi != null) return fi
        Node fieldNode = getFieldNode(fieldName)
        if (fieldNode == null) return null
        fi = new FieldInfo(this, fieldNode)
        fieldInfoMap.put(fieldName, fi)
        return fi
    }
    @CompileStatic
    ArrayList<FieldInfo> getNonPkFieldInfoList() {
        if (nonPkFieldInfoList != null) return nonPkFieldInfoList

        ArrayList<String> nonPkFieldNameList = getNonPkFieldNames()
        int nonPkFieldNameListSize = nonPkFieldNameList.size()
        ArrayList<FieldInfo> tempList = new ArrayList<>(nonPkFieldNameListSize)
        for (int i = 0; i < nonPkFieldNameListSize; i++) tempList.add(getFieldInfo(nonPkFieldNameList.get(i)))

        nonPkFieldInfoList = tempList
        return nonPkFieldInfoList
    }

    @CompileStatic
    static class FieldInfo {
        EntityDefinition ed
        Node fieldNode
        String name
        String type
        String columnName
        String fullColumnName = null
        String defaultStr
        String javaType = null
        int typeValue = -1
        boolean isPk
        boolean encrypt
        boolean isSimple
        boolean enableLocalization
        boolean isUserField
        boolean createOnly

        FieldInfo(EntityDefinition ed, Node fieldNode) {
            this.ed = ed
            this.fieldNode = fieldNode
            Map fnAttrs = fieldNode.attributes()
            name = (String) fnAttrs.get('name')
            type = (String) fnAttrs.get('type')
            columnName = (String) fnAttrs.get('column-name') ?: camelCaseToUnderscored(name)
            defaultStr = (String) fnAttrs.get('default')
            if (!type && fieldNode.get("complex-alias") && fnAttrs.get('function')) {
                // this is probably a calculated value, just default to number-decimal
                type = 'number-decimal'
            }
            if (type) {
                javaType = ed.efi.getFieldJavaType(type, ed) ?: 'String'
                typeValue = EntityFacadeImpl.getJavaTypeInt(javaType)
            } else {
                throw new EntityException("No type specified or found for field ${name} on entity ${ed.getFullEntityName()}")
            }
            isPk = 'true'.equals(fnAttrs.get('is-pk'))
            encrypt = 'true'.equals(fnAttrs.get('encrypt'))
            enableLocalization = 'true'.equals(fnAttrs.get('enable-localization'))
            isUserField = 'true'.equals(fnAttrs.get('is-user-field'))
            isSimple = !enableLocalization && !isUserField
            createOnly = fnAttrs.get('create-only') ? 'true'.equals(fnAttrs.get('create-only')) : ed.createOnly()
        }

        String getFullColumnName(boolean includeFunctionAndComplex) {
            if (fullColumnName != null) return fullColumnName

            if (ed.isViewEntity()) {
                // NOTE: for view-entity the incoming fieldNode will actually be for an alias element
                StringBuilder colNameBuilder = new StringBuilder()
                if (includeFunctionAndComplex) {
                    // column name for view-entity (prefix with "${entity-alias}.")
                    //colName.append(fieldNode."@entity-alias").append('.')
                    if (logger.isTraceEnabled()) logger.trace("For view-entity include function and complex not yet supported, for entity [${internalEntityName}], may get bad SQL...")
                }
                // else {

                if (fieldNode.get('complex-alias')) {
                    String function = fieldNode.attribute('function')
                    if (function) {
                        colNameBuilder.append(getFunctionPrefix(function))
                    }
                    ed.buildComplexAliasName(fieldNode, "+", colNameBuilder)
                    if (function) colNameBuilder.append(')')
                } else {
                    String function = fieldNode.attribute('function')
                    if (function) {
                        colNameBuilder.append(getFunctionPrefix(function))
                    }
                    // column name for view-entity (prefix with "${entity-alias}.")
                    colNameBuilder.append(fieldNode.attribute('entity-alias')).append('.')

                    String memberFieldName = fieldNode.attribute('field') ?: fieldNode.attribute('name')
                    colNameBuilder.append(ed.getBasicFieldColName(ed.internalEntityNode,
                            (String) fieldNode.attribute('entity-alias'), memberFieldName))

                    if (function) colNameBuilder.append(')')
                }

                // }
                fullColumnName = colNameBuilder.toString()
            } else {
                fullColumnName = columnName
            }

            return fullColumnName
        }
    }

    @CompileStatic
    protected Node makeUserFieldNode(EntityValue userField) {
        String fieldType = userField.fieldType ?: "text-long"
        if (fieldType == "text-very-long" || fieldType == "binary-very-long")
            throw new EntityException("UserField for entityName ${getFullEntityName()}, fieldName ${userField.fieldName} and userGroupId ${userField.userGroupId} has a fieldType that is not allowed: ${fieldType}")

        Node fieldNode = new Node(null, "field", [name: userField.fieldName, type: fieldType, "is-user-field": "true"])

        fieldNode.attributes().put("user-group-id", userField.userGroupId)
        if (userField.enableAuditLog == "Y") fieldNode.attributes().put("enable-audit-log", "true")
        if (userField.enableAuditLog == "U") fieldNode.attributes().put("enable-audit-log", "update")
        if (userField.enableLocalization == "Y") fieldNode.attributes().put("enable-localization", "true")
        if (userField.encrypt == "Y") fieldNode.attributes().put("encrypt", "true")

        return fieldNode
    }
    /*
    Node getRelationshipNode(String relationshipName) {
        Node relNode = relationshipNodeMap.get(relationshipName)
        if (relNode != null) return relNode

        String relatedEntityName = relationshipName.contains("#") ? relationshipName.substring(relationshipName.indexOf("#") + 1) : relationshipName
        String title = relationshipName.contains("#") ? relationshipName.substring(0, relationshipName.indexOf("#")) : null

        // if no 'title' see if relationshipName is actually a short-alias
        if (!title) {
            relNode = (Node) this.internalEntityNode."relationship".find({ it."@short-alias" == relationshipName })
            if (relNode != null) {
                // this isn't really necessary, we already found the relationship Node, but may be useful
                relatedEntityName = relNode."@related-entity-name"
                title = relNode."@title"
            }
        }

        if (relNode == null) {
            EntityDefinition relatedEd = null
            try {
                relatedEd = efi.getEntityDefinition(relatedEntityName)
            } catch (EntityException e) {
                // ignore if entity doesn't exist
                if (logger.isTraceEnabled()) logger.trace("Ignoring entity not found exception: ${e.toString()}")
                return null
            }

            relNode = (Node) this.internalEntityNode."relationship"
                    .find({ ((it."@title" ?: "") + it."@related-entity-name") == relationshipName ||
                        ((it."@title" ?: "") + "#" + it."@related-entity-name") == relationshipName ||
                        (relatedEd != null && it."@title" == title &&
                            (it."@related-entity-name" == relatedEd.getEntityName() ||
                                    it."@related-entity-name" == relatedEd.getFullEntityName())) })
        }

        // handle automatic reverse-many nodes (based on one node coming the other way)
        if (relNode == null) {
            // see if there is an entity matching the relationship name that has a relationship coming this way
            EntityDefinition relEd = null
            try {
                relEd = efi.getEntityDefinition(relatedEntityName)
            } catch (EntityException e) {
                // probably means not a valid entity name, which may happen a lot since we're checking here to see, so just ignore
                if (logger.isTraceEnabled()) logger.trace("Ignoring entity not found exception: ${e.toString()}")
            }
            if (relEd != null) {
                // don't call ed.getRelationshipNode(), may result in infinite recursion
                Node reverseRelNode = (Node) relEd.internalEntityNode."relationship".find(
                        { ((it."@related-entity-name" == this.internalEntityName || it."@related-entity-name" == this.fullEntityName)
                            && (it."@type" == "one" || it."@type" == "one-nofk") && ((!title && !it."@title") || it."@title" == title)) })
                if (reverseRelNode != null) {
                    Map keyMap = getRelationshipExpandedKeyMapInternal(reverseRelNode, this)
                    String relType = (this.getPkFieldNames() == relEd.getPkFieldNames()) ? "one-nofk" : "many"
                    Node newRelNode = this.internalEntityNode.appendNode("relationship",
                            ["related-entity-name":relatedEntityName, "type":relType])
                    if (title) newRelNode.attributes().put("title", title)
                    for (Map.Entry keyEntry in keyMap) {
                        // add a key-map with the reverse fields
                        newRelNode.appendNode("key-map", ["field-name":keyEntry.value, "related-field-name":keyEntry.key])
                    }
                    relNode = newRelNode
                }
            }
        }

        relationshipNodeMap.put(relationshipName, relNode)
        return relNode
    }
    */

    @CompileStatic
    static Map<String, String> getRelationshipExpandedKeyMapInternal(Node relationship, EntityDefinition relEd) {
        Map<String, String> eKeyMap = [:]
        NodeList keyMapList = (NodeList) relationship.get("key-map")
        if (!keyMapList && ((String) relationship.attribute('type')).startsWith('one')) {
            // go through pks of related entity, assume field names match
            for (String pkFieldName in relEd.getPkFieldNames()) eKeyMap.put(pkFieldName, pkFieldName)
        } else {
            for (Object childObj in keyMapList) {
                Node keyMap = (Node) childObj

                String fieldName = keyMap.attribute('field-name')
                String relFn = keyMap.attribute('related-field-name') ?: fieldName
                if (!relEd.isField(relFn) && ((String) relationship.attribute('type')).startsWith("one")) {
                    List<String> pks = relEd.getPkFieldNames()
                    if (pks.size() == 1) relFn = pks.get(0)
                    // if we don't match these constraints and get this default we'll get an error later...
                }
                eKeyMap.put(fieldName, relFn)
            }
        }
        return eKeyMap
    }

    @CompileStatic
    RelationshipInfo getRelationshipInfo(String relationshipName) {
        if (!relationshipName) return null
        return getRelationshipInfoMap().get(relationshipName)
    }

    @CompileStatic
    Map<String, RelationshipInfo> getRelationshipInfoMap() {
        if (relationshipInfoMap == null) makeRelInfoMap()
        return relationshipInfoMap
    }
    @CompileStatic
    private synchronized void makeRelInfoMap() {
        if (relationshipInfoMap != null) return
        Map<String, RelationshipInfo> relInfoMap = new HashMap<String, RelationshipInfo>()
        List<RelationshipInfo> relInfoList = getRelationshipsInfo(false)
        for (RelationshipInfo relInfo in relInfoList) {
            // always use the full relationshipName
            relInfoMap.put(relInfo.relationshipName, relInfo)
            // if there is a shortAlias add it under that
            if (relInfo.shortAlias) relInfoMap.put(relInfo.shortAlias, relInfo)
            // if there is no title, allow referring to the relationship by just the simple entity name (no package)
            if (!relInfo.title) relInfoMap.put(relInfo.relatedEd.getEntityName(), relInfo)
        }
        relationshipInfoMap = relInfoMap
    }

    @CompileStatic
    List<RelationshipInfo> getRelationshipsInfo(boolean dependentsOnly) {
        if (relationshipInfoList == null) makeRelInfoList()

        if (!dependentsOnly) return new ArrayList(relationshipInfoList)
        // just get dependents
        List<RelationshipInfo> infoListCopy = []
        for (RelationshipInfo info in relationshipInfoList) if (info.dependent) infoListCopy.add(info)
        return infoListCopy
    }
    private synchronized void makeRelInfoList() {
        if (relationshipInfoList != null) return

        if (!this.expandedRelationshipList) {
            // make sure this is done before as this isn't done by default
            if (!hasReverseRelationships) efi.createAllAutoReverseManyRelationships()
            this.expandedRelationshipList = this.internalEntityNode."relationship"
        }

        List<RelationshipInfo> infoList = []
        for (Node relNode in this.expandedRelationshipList) {
            RelationshipInfo relInfo = new RelationshipInfo(relNode, this, efi)
            infoList.add(relInfo)
        }
        relationshipInfoList = infoList
    }

    static class RelationshipInfo {
        String type
        boolean isTypeOne
        String title
        String relatedEntityName
        EntityDefinition fromEd
        EntityDefinition relatedEd
        Node relNode

        String relationshipName
        String shortAlias
        String prettyName
        Map keyMap
        boolean dependent

        RelationshipInfo(Node relNode, EntityDefinition fromEd, EntityFacadeImpl efi) {
            this.relNode = relNode
            type = relNode.attribute('type')
            isTypeOne = type.startsWith("one")
            title = relNode.attribute('title') ?: ''
            relatedEntityName = relNode.attribute('related-entity-name')
            this.fromEd = fromEd
            relatedEd = efi.getEntityDefinition(relatedEntityName)
            relatedEntityName = relatedEd.getFullEntityName()

            relationshipName = (title ? title + '#' : '') + relatedEntityName
            shortAlias = relNode.attribute('short-alias') ?: ''
            prettyName = relatedEd.getPrettyName(title, fromEd.internalEntityName)
            keyMap = getRelationshipExpandedKeyMapInternal(relNode, relatedEd)
            dependent = hasReverse()
        }

        private boolean hasReverse() {
            Node reverseRelNode = (Node) relatedEd.internalEntityNode."relationship".find(
                    { ((it."@related-entity-name" == fromEd.internalEntityName || it."@related-entity-name" == fromEd.fullEntityName)
                            && (it."@type" == "one" || it."@type" == "one-nofk")
                            && ((!title && !it."@title") || it."@title" == title)) })
            return reverseRelNode != null
        }

        @CompileStatic
        Map getTargetParameterMap(Map valueSource) {
            if (!valueSource) return [:]
            Map targetParameterMap = new HashMap()
            for (Map.Entry keyEntry in keyMap.entrySet()) {
                Object value = valueSource.get(keyEntry.key)
                if (!StupidUtilities.isEmpty(value)) targetParameterMap.put(keyEntry.value, value)
            }
            return targetParameterMap
        }

        String toString() { return "${relationshipName}${shortAlias ? ' (' + shortAlias + ')' : ''}, type ${type}, one? ${isTypeOne}, dependent? ${dependent}" }
    }

    @CompileStatic
    MasterDefinition getMasterDefinition(String name) {
        if (!name) name = "default"
        if (masterDefinitionMap == null) makeMasterDefinitionMap()
        return masterDefinitionMap.get(name)
    }

    private synchronized void makeMasterDefinitionMap() {
        Map<String, MasterDefinition> defMap = [:]
        for (Node masterNode in internalEntityNode."master") {
            MasterDefinition curDef = new MasterDefinition(this, masterNode)
            defMap.put(curDef.name, curDef)
        }
        masterDefinitionMap = defMap
    }

    @CompileStatic
    static class MasterDefinition {
        String name
        List<MasterDetail> detailList = []
        MasterDefinition(EntityDefinition ed, Node masterNode) {
            name = masterNode.attribute("name") ?: "default"
            List<Node> detailNodeList = masterNode.getAt("detail") as List<Node>
            for (Node detailNode in detailNodeList) detailList.add(new MasterDetail(ed, detailNode))
        }
    }
    @CompileStatic
    static class MasterDetail {
        String relationshipName
        EntityDefinition parentEd
        RelationshipInfo relInfo
        String relatedMasterName
        List<MasterDetail> internalDetailList = []
        MasterDetail(EntityDefinition parentEd, Node detailNode) {
            this.parentEd = parentEd
            relationshipName = detailNode.attribute("relationship")
            relInfo = parentEd.getRelationshipInfo(relationshipName)
            if (relInfo == null) throw new IllegalArgumentException("Invalid relationship name [${relationshipName}] for entity ${parentEd.getFullEntityName()}")
            // logger.warn("Following relationship ${relationshipName}")

            List<Node> detailNodeList = detailNode.getAt("detail") as List<Node>
            for (Node childNode in detailNodeList) internalDetailList.add(new MasterDetail(relInfo.relatedEd, childNode))

            relatedMasterName = (String) detailNode.attribute("use-master")
        }

        List<MasterDetail> getDetailList() {
            if (relatedMasterName) {
                List<MasterDetail> combinedList = new ArrayList<>(internalDetailList)
                MasterDefinition relatedMaster = relInfo.relatedEd.getMasterDefinition(relatedMasterName)
                if (relatedMaster == null) throw new IllegalArgumentException("Invalid use-master value [${relatedMasterName}], master not found in entity ${relInfo.relatedEntityName}")
                // logger.warn("Including master ${relatedMasterName} on entity ${relInfo.relatedEd.getFullEntityName()}")

                combinedList.addAll(relatedMaster.detailList)

                return combinedList
            } else {
                return internalDetailList
            }
        }
    }

    EntityDependents getDependentsTree() {
        EntityDependents edp = new EntityDependents(this, null, null)
        return edp
    }

    static class EntityDependents {
        String entityName
        EntityDefinition ed
        Map<String, EntityDependents> dependentEntities = new TreeMap()
        Set<String> descendants = new TreeSet()
        Map<String, RelationshipInfo> relationshipInfos = new HashMap()

        EntityDependents(EntityDefinition ed, Deque<String> ancestorEntities, Map<String, EntityDependents> allDependents) {
            this.ed = ed
            entityName = ed.fullEntityName

            if (ancestorEntities == null) ancestorEntities = new LinkedList()
            ancestorEntities.addFirst(entityName)
            if (allDependents == null) allDependents = new HashMap<String, EntityDependents>()
            allDependents.put(entityName, this)

            List<RelationshipInfo> relInfoList = ed.getRelationshipsInfo(true)
            for (RelationshipInfo relInfo in relInfoList) {
                if (!relInfo.dependent) continue
                descendants.add(relInfo.relatedEntityName)
                String relName = relInfo.relationshipName
                relationshipInfos.put(relName, relInfo)
                // if (relInfo.shortAlias) edp.relationshipInfos.put((String) relInfo.shortAlias, relInfo)
                EntityDefinition relEd = ed.efi.getEntityDefinition((String) relInfo.relatedEntityName)
                if (!dependentEntities.containsKey(relName) && !ancestorEntities.contains(relEd.fullEntityName)) {
                    EntityDependents relEdp = allDependents.get(relEd.fullEntityName)
                    if (relEdp == null) relEdp = new EntityDependents(relEd, ancestorEntities, allDependents)
                    dependentEntities.put(relName, relEdp)
                }
            }

            ancestorEntities.removeFirst()
        }

        TreeSet<String> getAllDescendants() {
            TreeSet<String> allSet = new TreeSet()
            populateAllDescendants(allSet)
            return allSet
        }
        protected void populateAllDescendants(TreeSet<String> allSet) {
            allSet.addAll(descendants)
            for (EntityDependents edp in dependentEntities.values()) edp.populateAllDescendants(allSet)
        }

        String toString() {
            StringBuilder builder = new StringBuilder(10000)
            Set<String> entitiesVisited = new HashSet<>()
            buildString(builder, 0, entitiesVisited)
            return builder.toString()
        }
        static final String indentBase = '- '
        void buildString(StringBuilder builder, int level, Set<String> entitiesVisited) {
            StringBuilder ib = new StringBuilder()
            for (int i = 0; i <= level; i++) ib.append(indentBase)
            String indent = ib.toString()

            for (Map.Entry<String, EntityDependents> entry in dependentEntities) {
                RelationshipInfo relInfo = relationshipInfos.get(entry.getKey())
                builder.append(indent).append(relInfo.relationshipName).append(' ').append(relInfo.keyMap).append('\n')
                if (level < 8 && !entitiesVisited.contains(entry.getValue().entityName)) {
                    entry.getValue().buildString(builder, level+1, entitiesVisited)
                    entitiesVisited.add(entry.getValue().entityName)
                } else if (entitiesVisited.contains(entry.getValue().entityName)) {
                    builder.append(indent).append(indentBase).append('Dependants already displayed\n')
                } else if (level == 8) {
                    builder.append(indent).append(indentBase).append('Reached level limit\n')
                }
            }
        }
    }

    String getPrettyName(String title, String baseName) {
        StringBuilder prettyName = new StringBuilder()
        for (String part in internalEntityName.split("(?=[A-Z])")) {
            if (baseName && part == baseName) continue
            if (prettyName) prettyName.append(" ")
            prettyName.append(part)
        }
        if (title) {
            boolean addParens = prettyName as boolean
            if (addParens) prettyName.append(" (")
            for (String part in title.split("(?=[A-Z])")) prettyName.append(part).append(" ")
            prettyName.deleteCharAt(prettyName.length()-1)
            if (addParens) prettyName.append(")")
        }
        return prettyName.toString()
    }

    @CompileStatic
    String getColumnName(String fieldName, boolean includeFunctionAndComplex) {
        FieldInfo fieldInfo = this.getFieldInfo(fieldName)
        if (fieldInfo == null) {
            throw new EntityException("Invalid field-name [${fieldName}] for the [${this.getFullEntityName()}] entity")
        }
        return fieldInfo.getFullColumnName(includeFunctionAndComplex)
    }

    protected String getBasicFieldColName(Node entityNode, String entityAlias, String fieldName) {
        Node memberEntity = (Node) entityNode."member-entity".find({ it."@entity-alias" == entityAlias })
        if (memberEntity == null) throw new EntityException("Could not find member-entity with entity-alias [${entityAlias}] in view-entity [${getFullEntityName()}]")
        EntityDefinition memberEd = this.efi.getEntityDefinition((String) memberEntity."@entity-name")
        return memberEd.getColumnName(fieldName, false)
    }

    protected void buildComplexAliasName(Node parentNode, String operator, StringBuilder colNameBuilder) {
        colNameBuilder.append('(')
        boolean isFirst = true
        for (Node childNode in (List<Node>) parentNode.children()) {
            if (isFirst) isFirst=false else colNameBuilder.append(' ').append(operator).append(' ')

            if (childNode.name() == "complex-alias") {
                buildComplexAliasName(childNode, (String) childNode."@operator", colNameBuilder)
            } else if (childNode.name() == "complex-alias-field") {
                String entityAlias = childNode."@entity-alias"
                String basicColName = getBasicFieldColName(internalEntityNode, entityAlias, (String) childNode."@field")
                String colName = entityAlias + "." + basicColName
                String defaultValue = childNode."@default-value"
                String function = childNode."@function"

                if (defaultValue) {
                    colName = "COALESCE(" + colName + "," + defaultValue + ")"
                }
                if (function) {
                    String prefix = getFunctionPrefix(function)
                    colName = prefix + colName + ")"
                }

                colNameBuilder.append(colName)
            }
        }
        colNameBuilder.append(')')
    }

    static protected String getFunctionPrefix(String function) {
        return (function == "count-distinct") ? "COUNT(DISTINCT " : function.toUpperCase() + '('
    }

    /** Returns the table name, ie table-name or converted entity-name */
    String getTableName() {
        if (this.internalEntityNode."@table-name") {
            return this.internalEntityNode."@table-name"
        } else {
            return camelCaseToUnderscored((String) this.internalEntityNode."@entity-name")
        }
    }

    String getFullTableName() {
        if (efi.getDatabaseNode(getEntityGroupName())?."@use-schemas" != "false") {
            String schemaName = getSchemaName()
            return schemaName ? schemaName + "." + getTableName() : getTableName()
        } else {
            return getTableName()
        }
    }

    String getSchemaName() {
        String schemaName = efi.getDatasourceNode(getEntityGroupName())?."@schema-name"
        return schemaName ?: null
    }

    @CompileStatic
    boolean isField(String fieldName) { return getFieldNode(fieldName) != null }
    @CompileStatic
    boolean isPkField(String fieldName) {
        FieldInfo fieldInfo = getFieldInfo(fieldName)
        if (fieldInfo == null) return false
        return fieldInfo.isPk
    }
    @CompileStatic
    boolean isSimpleField(String fieldName) {
        FieldInfo fieldInfo = getFieldInfo(fieldName)
        if (fieldInfo == null) return false
        return fieldInfo.isSimple
    }

    @CompileStatic
    boolean containsPrimaryKey(Map fields) {
        if (!fields) return false
        ArrayList<String> fieldNameList = this.getPkFieldNames()
        if (!fieldNameList) return false
        int size = fieldNameList.size()
        for (int i = 0; i < size; i++) {
            String fieldName = fieldNameList.get(i)
            if (!fields.get(fieldName)) return false
        }
        return true
    }

    @CompileStatic
    Map<String, Object> getPrimaryKeys(Map fields) {
        Map<String, Object> pks = new HashMap()
        ArrayList<String> fieldNameList = this.getPkFieldNames()
        int size = fieldNameList.size()
        for (int i = 0; i < size; i++) {
            String fieldName = fieldNameList.get(i)
            pks.put(fieldName, fields.get(fieldName))
        }
        return pks
    }

    @CompileStatic
    ArrayList<String> getFieldNames(boolean includePk, boolean includeNonPk, boolean includeUserFields) {
        ArrayList<String> baseList
        // common case, do it fast
        if (includePk) {
            if (includeNonPk) {
                baseList = getAllFieldNames(false)
            } else {
                baseList = getPkFieldNames()
            }
        } else {
            if (includeNonPk) {
                baseList = getNonPkFieldNames()
            } else {
                // all false is weird, but okay
                baseList = new ArrayList<String>()
            }
        }
        if (!includeUserFields) return baseList

        ListOrderedSet userFieldNames = getUserFieldNames()
        if (userFieldNames) {
            List<String> returnList = new ArrayList<String>()
            returnList.addAll(baseList)
            returnList.addAll(userFieldNames.asList())
            return returnList
        } else {
            return baseList
        }
    }
    @CompileStatic
    protected ListOrderedSet getFieldNamesInternal(boolean includePk, boolean includeNonPk) {
        ListOrderedSet nameSet = new ListOrderedSet()
        String nodeName = this.isViewEntity() ? "alias" : "field"
        for (Object nodeObj in (NodeList) this.internalEntityNode.get(nodeName)) {
            Node node = (Node) nodeObj
            if ((includePk && 'true'.equals(node.attribute('is-pk'))) || (includeNonPk && !'true'.equals(node.attribute('is-pk')))) {
                nameSet.add(node.attribute('name'))
            }
        }
        return nameSet
    }
    @CompileStatic
    protected ListOrderedSet getUserFieldNames() {
        ListOrderedSet userFieldNames = null
        if (allowUserField && !this.isViewEntity() && (hasUserFields == null || hasUserFields)) {
            boolean alreadyDisabled = efi.getEcfi().getExecutionContext().getArtifactExecution().disableAuthz()
            try {
                EntityList userFieldList = efi.find("moqui.entity.UserField").condition("entityName", getFullEntityName()).useCache(true).list()
                if (userFieldList) {
                    hasUserFields = true
                    userFieldNames = new ListOrderedSet()

                    Set<String> userGroupIdSet = efi.getEcfi().getExecutionContext().getUser().getUserGroupIdSet()
                    for (EntityValue userField in userFieldList) {
                        if (userGroupIdSet.contains(userField.get('userGroupId'))) userFieldNames.add((String) userField.get('fieldName'))
                    }
                } else {
                    hasUserFields = false
                }
            } finally {
                if (!alreadyDisabled) efi.getEcfi().getExecutionContext().getArtifactExecution().enableAuthz()
            }
        }
        return userFieldNames
    }

    @CompileStatic
    ArrayList<String> getPkFieldNames() {
        if (pkFieldNameList == null)
            pkFieldNameList = new ArrayList(getFieldNamesInternal(true, false))
        return pkFieldNameList
    }
    @CompileStatic
    ArrayList<String> getNonPkFieldNames() {
        if (nonPkFieldNameList == null)
            nonPkFieldNameList = new ArrayList(getFieldNamesInternal(false, true))
        return nonPkFieldNameList
    }
    @CompileStatic
    ArrayList<String> getAllFieldNames() { return getAllFieldNames(true) }
    @CompileStatic
    ArrayList<String> getAllFieldNames(boolean includeUserFields) {
        if (allFieldNameList == null)
            allFieldNameList = new ArrayList(getFieldNamesInternal(true, true))

        if (!includeUserFields) return allFieldNameList

        ListOrderedSet userFieldNames = getUserFieldNames()
        if (userFieldNames) {
            List<String> returnList = new ArrayList<>(allFieldNameList.size() + userFieldNames.size())
            returnList.addAll(allFieldNameList)
            returnList.addAll(userFieldNames.asList())
            return returnList
        } else {
            return allFieldNameList
        }
    }

    Map<String, String> pkFieldDefaults = null
    Map<String, String> nonPkFieldDefaults = null
    @CompileStatic
    Map<String, String> getPkFieldDefaults() {
        if (pkFieldDefaults == null) {
            Map<String, String> newDefaults = [:]
            for (Node fieldNode in getFieldNodes(true, false, false)) {
                String defaultStr = fieldNode.attribute('default')
                if (!defaultStr) continue
                newDefaults.put((String) fieldNode.attribute('name'), defaultStr)
            }
            pkFieldDefaults = newDefaults
        }
        return pkFieldDefaults
    }
    @CompileStatic
    Map<String, String> getNonPkFieldDefaults() {
        if (nonPkFieldDefaults == null) {
            Map<String, String> newDefaults = [:]
            for (Node fieldNode in getFieldNodes(false, true, false)) {
                String defaultStr = fieldNode.attribute('default')
                if (!defaultStr) continue
                newDefaults.put((String) fieldNode.attribute('name'), defaultStr)
            }
            nonPkFieldDefaults = newDefaults
        }
        return nonPkFieldDefaults
    }

    protected static final Map<String, String> fieldTypeJsonMap = [
            "id":"string", "id-long":"string", "text-indicator":"string", "text-short":"string", "text-medium":"string",
            "text-long":"string", "text-very-long":"string", "date-time":"string", "time":"string",
            "date":"string", "number-integer":"number", "number-float":"number",
            "number-decimal":"number", "currency-amount":"number", "currency-precise":"number",
            "binary-very-long":"string" ] // NOTE: binary-very-long may need hyper-schema stuff
    static final Map paginationParameters =
            [type:'object', properties:
                    [pageIndex:[type:'number', description:'Page number to return, starting with zero'],
                     pageSize:[type:'number', description:'Number of records per page (default 100)'],
                     orderByField:[type:'string', description:'Field name to order by (or comma separated names)'],
                     pageNoLimit:[type:'string', description:'If true don\'t limit page size (no pagination)'],
                     dependentLevels:[type:'number', description:'Levels of dependent child records to include']
                    ]
            ]

    @CompileStatic
    List<String> getFieldEnums(FieldInfo fi) {
        // populate enum values for Enumeration and StatusItem
        // find first relationship that has this field as the only key map and is not a many relationship
        RelationshipInfo oneRelInfo = null
        List<RelationshipInfo> allRelInfoList = getRelationshipsInfo(false)
        for (RelationshipInfo relInfo in allRelInfoList) {
            Map km = relInfo.keyMap
            if (km.size() == 1 && km.containsKey(fi.name) && relInfo.type == "one" && relInfo.relNode.attribute("is-auto-reverse") != "true") {
                oneRelInfo = relInfo
                break;
            }
        }
        if (oneRelInfo != null && oneRelInfo.title) {
            if (oneRelInfo.relatedEd.getFullEntityName() == 'moqui.basic.Enumeration') {
                EntityList enumList = efi.find("moqui.basic.Enumeration").condition("enumTypeId", oneRelInfo.title)
                        .orderBy("sequenceNum,enumId").disableAuthz().list()
                if (enumList) {
                    List<String> enumIdList = [null]
                    for (EntityValue ev in enumList) enumIdList.add((String) ev.enumId)
                    return enumIdList
                }
            } else if (oneRelInfo.relatedEd.getFullEntityName() == 'moqui.basic.StatusItem') {
                EntityList statusList = efi.find("moqui.basic.StatusItem").condition("statusTypeId", oneRelInfo.title)
                        .orderBy("sequenceNum,statusId").disableAuthz().list()
                if (statusList) {
                    List<String> statusIdList = [null]
                    for (EntityValue ev in statusList) statusIdList.add((String) ev.statusId)
                    return statusIdList
                }
            }
        }
        return null
    }

    @CompileStatic
    Map getJsonSchema(boolean standalone, Map<String, Object> definitionsMap, String schemaUri, String linkPrefix, String schemaLinkPrefix) {
        String name = getShortAlias() ?: getFullEntityName()
        String prettyName = getPrettyName(null, null)

        Map<String, Object> properties = [:]
        properties.put('_entity', [type:'string', default:name])
        Map<String, Object> schema = [id:name, title:prettyName, type:'object', properties:properties] as Map<String, Object>

        // add all fields
        ArrayList<String> allFields = getAllFieldNames(true)
        for (int i = 0; i < allFields.size(); i++) {
            FieldInfo fi = getFieldInfo(allFields.get(i))
            Map<String, Object> propMap = [:]
            propMap.put('type', fieldTypeJsonMap.get(fi.type))
            if (fi.type == 'date-time') propMap.put('format', 'date-time')
            properties.put(fi.getName(), propMap)

            List enumList = getFieldEnums(fi)
            if (enumList) propMap.put('enum', enumList)
        }


        // put current schema in Map before nesting for relationships, avoid infinite recursion with entity rel loops
        if (standalone && definitionsMap == null) {
            definitionsMap = [:]
            definitionsMap.put('paginationParameters', paginationParameters)
        }
        if (definitionsMap != null && !definitionsMap.containsKey(name))
            definitionsMap.put(name, schema)

        // add all relationships, nest
        List<RelationshipInfo> relInfoList = getRelationshipsInfo(true)
        for (RelationshipInfo relInfo in relInfoList) {
            String relationshipName = relInfo.relationshipName
            String entryName = relInfo.shortAlias ?: relationshipName
            String relatedRefName = relInfo.relatedEd.shortAlias ?: relInfo.relatedEd.getFullEntityName()

            // recurse, let it put itself in the definitionsMap
            if (definitionsMap != null && !definitionsMap.containsKey(relatedRefName))
                relInfo.relatedEd.getJsonSchema(false, definitionsMap, schemaUri, linkPrefix, schemaLinkPrefix)

            if (relInfo.type == "many") {
                properties.put(entryName, [type:'array', items:['$ref':('#/definitions/' + relatedRefName)]])
            } else {
                properties.put(entryName, ['$ref':('#/definitions/' + relatedRefName)])
            }
        }

        // add links (for Entity REST API)
        if (linkPrefix) {
            List<String> pkNameList = getPkFieldNames()
            StringBuilder idSb = new StringBuilder()
            for (String pkName in pkNameList) idSb.append('/{').append(pkName).append('}')
            String idString = idSb.toString()

            List linkList = [
                [rel:'self', method:'GET', href:"${linkPrefix}/${name}${idString}", title:"Get single ${prettyName}",
                    targetSchema:['$ref':"#/definitions/${name}"]],
                [rel:'instances', method:'GET', href:"${linkPrefix}/${name}", title:"Get list of ${prettyName}",
                    schema:[allOf:[['$ref':'#/definitions/paginationParameters'], ['$ref':"#/definitions/${name}"]]],
                    targetSchema:[type:'array', items:['$ref':"#/definitions/${name}"]]],
                [rel:'create', method:'POST', href:"${linkPrefix}/${name}", title:"Create ${prettyName}",
                    schema:['$ref':"#/definitions/${name}"]],
                [rel:'update', method:'PATCH', href:"${linkPrefix}/${name}${idString}", title:"Update ${prettyName}",
                    schema:['$ref':"#/definitions/${name}"]],
                [rel:'store', method:'PUT', href:"${linkPrefix}/${name}${idString}", title:"Create or Update ${prettyName}",
                    schema:['$ref':"#/definitions/${name}"]],
                [rel:'destroy', method:'DELETE', href:"${linkPrefix}/${name}${idString}", title:"Delete ${prettyName}",
                    schema:['$ref':"#/definitions/${name}"]]
            ]
            if (schemaLinkPrefix) linkList.add([rel:'describedBy', method:'GET', href:"${schemaLinkPrefix}/${name}", title:"Get schema for ${prettyName}"])

            schema.put('links', linkList)
        }

        if (standalone) {
            return ['$schema':'http://json-schema.org/draft-04/hyper-schema#', id:"${schemaUri}/${name}",
                    '$ref':"#/definitions/${name}", definitions:definitionsMap]
        } else {
            return schema
        }
    }

    static final Map ramlPaginationParameters = [
             pageIndex:[type:'number', description:'Page number to return, starting with zero'],
             pageSize:[type:'number', default:100, description:'Number of records per page (default 100)'],
             orderByField:[type:'string', description:'Field name to order by (or comma separated names)'],
             pageNoLimit:[type:'string', description:'If true don\'t limit page size (no pagination)'],
             dependentLevels:[type:'number', description:'Levels of dependent child records to include']
            ]

    @CompileStatic
    Map getRamlApi() {
        String name = getShortAlias() ?: getFullEntityName()
        String prettyName = getPrettyName(null, null)

        Map<String, Object> ramlMap = [:]

        // setup field info
        Map qpMap = [:]
        ArrayList<String> allFields = getAllFieldNames(true)
        for (int i = 0; i < allFields.size(); i++) {
            FieldInfo fi = getFieldInfo(allFields.get(i))
            Map<String, Object> propMap = [:]
            propMap.put('type', fieldTypeJsonMap.get(fi.type))
            qpMap.put(fi.getName(), propMap)

            List enumList = getFieldEnums(fi)
            if (enumList) propMap.put('enum', enumList)
        }

        // get list
        // TODO: make body array of schema
        ramlMap.put('get', [is:['paged'], description:"Get list of ${prettyName}".toString(), queryParameters:qpMap,
                            responses:[200:[body:['application/json': [schema:name]]]]])
        // create
        ramlMap.put('post', [description:"Create ${prettyName}".toString(), body:['application/json': [schema:name]]])

        // under IDs for single record operations
        List<String> pkNameList = getPkFieldNames()
        Map recordMap = ramlMap
        for (String pkName in pkNameList) {
            Map childMap = [:]
            recordMap.put('/{' + pkName + '}', childMap)
            recordMap = childMap
        }

        // get single
        recordMap.put('get', [description:"Get single ${prettyName}".toString(),
                            responses:[200:[body:['application/json': [schema:name]]]]])
        // update
        recordMap.put('patch', [description:"Update ${prettyName}".toString(), body:['application/json': [schema:name]]])
        // store
        recordMap.put('put', [description:"Create or Update ${prettyName}".toString(), body:['application/json': [schema:name]]])
        // delete
        recordMap.put('delete', [description:"Delete ${prettyName}".toString()])

        return ramlMap
    }

    List<Node> getFieldNodes(boolean includePk, boolean includeNonPk, boolean includeUserFields) {
        // NOTE: this is not necessarily the fastest way to do this, if it becomes a performance problem replace it with a local List of field Nodes
        List<Node> nodeList = new ArrayList<Node>()
        String nodeName = this.isViewEntity() ? "alias" : "field"
        for (Object nodeObj in (NodeList) this.internalEntityNode.get(nodeName)) {
            Node node = (Node) nodeObj
            if ((includePk && node."@is-pk" == "true") || (includeNonPk && node."@is-pk" != "true")) {
                nodeList.add(node)
            }
        }

        if (includeUserFields && allowUserField && !this.isViewEntity()) {
            boolean alreadyDisabled = efi.getEcfi().getExecutionContext().getArtifactExecution().disableAuthz()
            try {
                EntityList userFieldList = efi.find("moqui.entity.UserField").condition("entityName", getFullEntityName()).useCache(true).list()
                if (userFieldList) {
                    Set<String> userGroupIdSet = efi.getEcfi().getExecutionContext().getUser().getUserGroupIdSet()
                    for (EntityValue userField in userFieldList) {
                        if (userGroupIdSet.contains(userField.userGroupId)) {
                            nodeList.add(makeUserFieldNode(userField))
                            break
                        }
                    }
                }
            } finally {
                if (!alreadyDisabled) efi.getEcfi().getExecutionContext().getArtifactExecution().enableAuthz()
            }
        }

        return nodeList
    }

    Map getMePkFieldToAliasNameMap(String entityAlias) {
        if (mePkFieldToAliasNameMapMap == null) mePkFieldToAliasNameMapMap = new HashMap<String, Map>()
        Map mePkFieldToAliasNameMap = mePkFieldToAliasNameMapMap.get(entityAlias)

        //logger.warn("TOREMOVE 1 getMePkFieldToAliasNameMap entityAlias=${entityAlias} cached value=${mePkFieldToAliasNameMap}; entityNode=${entityNode}")
        if (mePkFieldToAliasNameMap != null) return mePkFieldToAliasNameMap

        mePkFieldToAliasNameMap = new HashMap()

        // do a reverse map on member-entity pk fields to view-entity aliases
        Node memberEntityNode = (Node) entityNode."member-entity".find({ it."@entity-alias" == entityAlias })
        //logger.warn("TOREMOVE 2 getMePkFieldToAliasNameMap entityAlias=${entityAlias} memberEntityNode=${memberEntityNode}")
        EntityDefinition med = this.efi.getEntityDefinition((String) memberEntityNode."@entity-name")
        List<String> pkFieldNames = med.getPkFieldNames()
        for (String pkName in pkFieldNames) {
            Node matchingAliasNode = (Node) entityNode."alias".find({
                it."@entity-alias" == memberEntityNode."@entity-alias" &&
                (it."@field" == pkName || (!it."@field" && it."@name" == pkName)) })
            //logger.warn("TOREMOVE 3 getMePkFieldToAliasNameMap entityAlias=${entityAlias} for pkName=${pkName}, matchingAliasNode=${matchingAliasNode}")
            if (matchingAliasNode) {
                // found an alias Node
                mePkFieldToAliasNameMap.put(pkName, matchingAliasNode."@name")
                continue
            }

            // no alias, try to find in join key-maps that map to other aliased fields

            // first try the current member-entity
            if (memberEntityNode."@join-from-alias" && memberEntityNode."key-map") {
                boolean foundOne = false
                for (Node keyMapNode in memberEntityNode."key-map") {
                    //logger.warn("TOREMOVE 4 getMePkFieldToAliasNameMap entityAlias=${entityAlias} for pkName=${pkName}, keyMapNode=${keyMapNode}")
                    if (keyMapNode."@related-field-name" == pkName ||
                            (!keyMapNode."@related-field-name" && keyMapNode."@field-name" == pkName)) {
                        String relatedPkName = keyMapNode."@field-name"
                        Node relatedMatchingAliasNode = (Node) entityNode."alias".find({
                            it."@entity-alias" == memberEntityNode."@join-from-alias" &&
                            (it."@field" == relatedPkName || (!it."@field" && it."@name" == relatedPkName)) })
                        //logger.warn("TOREMOVE 5 getMePkFieldToAliasNameMap entityAlias=${entityAlias} for pkName=${pkName}, relatedAlias=${memberEntityNode.'@join-from-alias'}, relatedPkName=${relatedPkName}, relatedMatchingAliasNode=${relatedMatchingAliasNode}")
                        if (relatedMatchingAliasNode) {
                            mePkFieldToAliasNameMap.put(pkName, relatedMatchingAliasNode."@name")
                            foundOne = true
                            break
                        }
                    }
                }
                if (foundOne) continue
            }

            // then go through all other member-entity that might relate back to this one
            for (Node relatedMeNode in entityNode."member-entity") {
                if (relatedMeNode."@join-from-alias" == memberEntityNode."@entity-alias" && relatedMeNode."key-map") {
                    boolean foundOne = false
                    for (Node keyMapNode in relatedMeNode."key-map") {
                        if (keyMapNode."@field-name" == pkName) {
                            String relatedPkName = keyMapNode."@related-field-name" ?: keyMapNode."@field-name"
                            Node relatedMatchingAliasNode = (Node) entityNode."alias".find({
                                it."@entity-alias" == relatedMeNode."@entity-alias" &&
                                (it."@field" == relatedPkName || (!it."@field" && it."@name" == relatedPkName)) })
                            if (relatedMatchingAliasNode) {
                                mePkFieldToAliasNameMap.put(pkName, relatedMatchingAliasNode."@name")
                                foundOne = true
                                break
                            }
                        }
                    }
                    if (foundOne) break
                }
            }
        }

        if (pkFieldNames.size() != mePkFieldToAliasNameMap.size()) {
            logger.warn("Not all primary-key fields in view-entity [${fullEntityName}] for member-entity [${memberEntityNode.'@entity-name'}], skipping cache reverse-association, and note that if this record is updated the cache won't automatically clear; pkFieldNames=${pkFieldNames}; partial mePkFieldToAliasNameMap=${mePkFieldToAliasNameMap}")
        }

        return mePkFieldToAliasNameMap
    }

    @CompileStatic
    Map cloneMapRemoveFields(Map theMap, Boolean pks) {
        Map newMap = new HashMap(theMap)
        ArrayList<String> fieldNameList = (pks != null ? this.getFieldNames(pks, !pks, !pks) : this.getAllFieldNames())
        int size = fieldNameList.size()
        for (int i = 0; i < size; i++) {
            String fieldName = fieldNameList.get(i)
            if (newMap.containsKey(fieldName)) newMap.remove(fieldName)
        }
        return newMap
    }

    @CompileStatic
    void setFields(Map<String, Object> src, Map<String, Object> dest, boolean setIfEmpty, String namePrefix, Boolean pks) {
        if (src == null || dest == null) return
        boolean destIsEntityValueBase = dest instanceof EntityValueBase
        EntityValueBase destEvb = destIsEntityValueBase ? (EntityValueBase) dest : null

        boolean hasNamePrefix = namePrefix as boolean
        EntityValueBase evb = src instanceof EntityValueBase ? (EntityValueBase) src : null
        ArrayList<String> fieldNameList = pks != null ? this.getFieldNames(pks, !pks, !pks) : this.getAllFieldNames()
        // use integer iterator, saves quite a bit of time, improves time for this method by about 20% with this alone
        int size = fieldNameList.size()
        for (int i = 0; i < size; i++) {
            String fieldName = fieldNameList.get(i)
            String sourceFieldName
            if (hasNamePrefix) {
                sourceFieldName = namePrefix + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1)
            } else {
                sourceFieldName = fieldName
            }

            Object value = src.get(sourceFieldName)
            if (value != null || (evb != null ? evb.isFieldSet(sourceFieldName) : src.containsKey(sourceFieldName))) {
                boolean isCharSequence = false
                boolean isEmpty = false
                if (value == null) {
                    isEmpty = true
                } else if (value instanceof CharSequence) {
                    isCharSequence = true
                    if (value.length() == 0) isEmpty = true
                }

                if (!isEmpty) {
                    if (isCharSequence) {
                        try {
                            if (value instanceof String) {
                                Object converted = convertFieldString(fieldName, value)
                                if (destIsEntityValueBase) destEvb.putNoCheck(fieldName, converted) else dest.put(fieldName, converted)
                            } else {
                                Object converted = convertFieldString(fieldName, value.toString())
                                if (destIsEntityValueBase) destEvb.putNoCheck(fieldName, converted) else dest.put(fieldName, converted)
                            }
                        } catch (BaseException be) {
                            this.efi.ecfi.executionContext.message.addValidationError(null, fieldName, null, be.getMessage(), be)
                        }
                    } else {
                        if (destIsEntityValueBase) destEvb.putNoCheck(fieldName, value) else dest.put(fieldName, value)
                    }
                } else if (setIfEmpty && src.containsKey(sourceFieldName)) {
                    // treat empty String as null, otherwise set as whatever null or empty type it is
                    if (value != null && isCharSequence) {
                        if (destIsEntityValueBase) destEvb.putNoCheck(fieldName, null) else dest.put(fieldName, null)
                    } else {
                        if (destIsEntityValueBase) destEvb.putNoCheck(fieldName, value) else dest.put(fieldName, value)
                    }
                }
            }
        }
    }

    @CompileStatic
    Object convertFieldString(String name, String value) {
        if ('null'.equals(value)) value = null
        if (value == null) return null

        Object outValue
        FieldInfo fieldInfo = getFieldInfo(name)
        if (fieldInfo == null) throw new EntityException("The name [${name}] is not a valid field name for entity [${entityName}]")

        String javaType = fieldInfo.javaType
        int typeValue = fieldInfo.typeValue

        // String javaType = fieldType ? (EntityFacadeImpl.fieldTypeJavaMap.get(fieldType) ?: efi.getFieldJavaType(fieldType, this)) : 'String'
        // Integer typeValue = (fieldType ? EntityFacadeImpl.fieldTypeIntMap.get(fieldType) : null) ?: EntityFacadeImpl.getJavaTypeInt(javaType)

        boolean isEmpty = value.length() == 0

        try {
            switch (typeValue) {
                case 1: outValue = value; break
                case 2: // outValue = java.sql.Timestamp.valueOf(value);
                    if (isEmpty) { outValue = null; break }
                    outValue = efi.getEcfi().getL10nFacade().parseTimestamp(value, null)
                    if (((Object) outValue) == null) throw new BaseException("The value [${value}] is not a valid date/time")
                    break
                case 3: // outValue = java.sql.Time.valueOf(value);
                    if (isEmpty) { outValue = null; break }
                    outValue = efi.getEcfi().getL10nFacade().parseTime(value, null)
                    if (outValue == null) throw new BaseException("The value [${value}] is not a valid time")
                    break
                case 4: // outValue = java.sql.Date.valueOf(value);
                    if (isEmpty) { outValue = null; break }
                    outValue = efi.getEcfi().getL10nFacade().parseDate(value, null)
                    if (outValue == null) throw new BaseException("The value [${value}] is not a valid date")
                    break
                case 5: // outValue = Integer.valueOf(value); break
                case 6: // outValue = Long.valueOf(value); break
                case 7: // outValue = Float.valueOf(value); break
                case 8: // outValue = Double.valueOf(value); break
                case 9: // outValue = new BigDecimal(value); break
                    if (isEmpty) { outValue = null; break }
                    BigDecimal bdVal = efi.getEcfi().getL10nFacade().parseNumber(value, null)
                    if (bdVal == null) {
                        throw new BaseException("The value [${value}] is not valid for type [${javaType}]")
                    } else {
                        outValue = StupidUtilities.basicConvert(bdVal.stripTrailingZeros(), javaType)
                    }
                    break
                case 10:
                    if (isEmpty) { outValue = null; break }
                    outValue = Boolean.valueOf(value); break
                case 11: outValue = value; break
                case 12: outValue = new SerialBlob(value.getBytes()); break
                case 13: outValue = value; break
                case 14:
                    if (isEmpty) { outValue = null; break }
                    outValue = value as Date; break
            // better way for Collection (15)? maybe parse comma separated, but probably doesn't make sense in the first place
                case 15: outValue = value; break
                default: outValue = value; break
            }
        } catch (IllegalArgumentException e) {
            throw new BaseException("The value [${value}] is not valid for type [${javaType}]", e)
        }

        return outValue
    }

    @CompileStatic
    String getFieldString(String name, Object value) {
        if (value == null) return null

        String outValue
        FieldInfo fieldInfo = getFieldInfo(name)

        String javaType = fieldInfo.javaType
        int typeValue = fieldInfo.typeValue

        try {
            switch (typeValue) {
                case 1: outValue = value; break
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                case 8:
                case 9:
                    if (value instanceof BigDecimal) value = ((BigDecimal) value).stripTrailingZeros()
                    outValue = efi.getEcfi().getL10nFacade().format(value, null)
                    break
                case 10: outValue = value.toString(); break
                case 11: outValue = value; break
                case 12:
                    if (value instanceof byte[]) {
                        outValue = new String(Base64.encodeBase64((byte[]) value));
                    } else {
                        logger.info("Field [${name}] on entity [${entityName}] is not of type 'byte[]', is [${value}] so using plain toString()")
                        outValue = value.toString()
                    }
                    break
                case 13: outValue = value; break
                case 14: outValue = value.toString(); break
            // better way for Collection (15)? maybe parse comma separated, but probably doesn't make sense in the first place
                case 15: outValue = value; break
                default: outValue = value; break
            }
        } catch (IllegalArgumentException e) {
            throw new BaseException("The value [${value}] is not valid for type [${javaType}]", e)
        }

        return outValue
    }

    @CompileStatic
    String getFieldStringForFile(String name, Object value) {
        if (value == null) return null

        String outValue
        if (value instanceof Timestamp) {
            // use a Long number, no TZ issues
            outValue = value.getTime() as String
        } else if (value instanceof BigDecimal) {
            outValue = value.toPlainString()
        } else {
            outValue = getFieldString(name, value)
        }

        return outValue
    }

    protected void expandAliasAlls() {
        if (!isViewEntity()) return
        for (Node aliasAll: this.internalEntityNode."alias-all") {
            Node memberEntity = (Node) this.internalEntityNode."member-entity".find({ it."@entity-alias" == aliasAll."@entity-alias" })
            if (!memberEntity) {
                logger.error("In alias-all with entity-alias [${aliasAll."@entity-alias"}], member-entity with same entity-alias not found, ignoring")
                continue;
            }

            EntityDefinition aliasedEntityDefinition = this.efi.getEntityDefinition((String) memberEntity."@entity-name")
            if (!aliasedEntityDefinition) {
                logger.error("Entity [${memberEntity."@entity-name"}] referred to in member-entity with entity-alias [${aliasAll."@entity-alias"}] not found, ignoring")
                continue;
            }

            for (Node fieldNode in aliasedEntityDefinition.getFieldNodes(true, true, false)) {
                // never auto-alias these
                if (fieldNode."@name" == "lastUpdatedStamp") continue
                // if specified as excluded, leave it out
                if (aliasAll."exclude".find({ it."@field" == fieldNode."@name"})) continue

                String aliasName = fieldNode."@name"
                if (aliasAll."@prefix") {
                    StringBuilder newAliasName = new StringBuilder((String) aliasAll."@prefix")
                    newAliasName.append(Character.toUpperCase(aliasName.charAt(0)))
                    newAliasName.append(aliasName.substring(1))
                    aliasName = newAliasName.toString()
                }

                Node existingAliasNode = (Node) this.internalEntityNode.alias.find({ it."@name" == aliasName })
                if (existingAliasNode) {
                    //log differently if this is part of a member-entity view link key-map because that is a common case when a field will be auto-expanded multiple times
                    boolean isInViewLink = false
                    for (Node viewMeNode in this.internalEntityNode."member-entity") {
                        boolean isRel = false
                        if (viewMeNode."@entity-alias" == aliasAll."@entity-alias") {
                            isRel = true
                        } else if (!viewMeNode."@join-from-alias" == aliasAll."@entity-alias") {
                            // not the rel-entity-alias or the entity-alias, so move along
                            continue;
                        }
                        for (Node keyMap in viewMeNode."key-map") {
                            if (!isRel && keyMap."@field-name" == fieldNode."@name") {
                                isInViewLink = true
                                break
                            } else if (isRel && (keyMap."@related-field-name" ?: keyMap."@field-name") == fieldNode."@name") {
                                isInViewLink = true
                                break
                            }
                        }
                        if (isInViewLink) break
                    }

                    // already exists... probably an override, but log just in case
                    String warnMsg = "Throwing out field alias in view entity " + this.getFullEntityName() +
                            " because one already exists with the alias name [" + aliasName + "] and field name [" +
                            memberEntity."@entity-alias" + "(" + aliasedEntityDefinition.getFullEntityName() + ")." +
                            fieldNode."@name" + "], existing field name is [" + existingAliasNode."@entity-alias" + "." +
                            existingAliasNode."@field" + "]"
                    if (isInViewLink) {if (logger.isTraceEnabled()) logger.trace(warnMsg)} else {logger.info(warnMsg)}

                    // ship adding the new alias
                    continue
                }

                Node newAlias = this.internalEntityNode.appendNode("alias",
                        [name:aliasName, field:fieldNode."@name", "entity-alias":aliasAll."@entity-alias",
                        "if-from-alias-all":true])
                if (fieldNode."description") newAlias.appendNode(fieldNode."description")
            }
        }
    }

    EntityConditionImplBase makeViewWhereCondition() {
        if (!this.isViewEntity()) return null
        // add the view-entity.entity-condition.econdition(s)
        Node entityCondition = this.internalEntityNode."entity-condition"[0]
        return makeViewListCondition(entityCondition)
    }
    protected EntityConditionImplBase makeViewListCondition(Node conditionsParent) {
        if (conditionsParent == null) return null
        List<EntityConditionImplBase> condList = new ArrayList()
        for (Node dateFilter in conditionsParent."date-filter") {
            // NOTE: this doesn't do context expansion of the valid-date as it doesn't make sense for an entity def to depend on something being in the context
            condList.add((EntityConditionImplBase) this.efi.conditionFactory.makeConditionDate(
                    (String) dateFilter."@from-field-name", (String) dateFilter."@thru-field-name",
                    dateFilter."@valid-date" ? efi.getEcfi().getResourceFacade().expand((String) dateFilter."@valid-date", "") as Timestamp : null))
        }
        for (Node econdition in conditionsParent."econdition") {
            EntityConditionImplBase cond;
            ConditionField field
            if (econdition."@entity-alias") {
                Node memberEntity = (Node) this.internalEntityNode."member-entity".find({ it."@entity-alias" == econdition."@entity-alias"})
                if (!memberEntity) throw new EntityException("The entity-alias [${econdition."@entity-alias"}] was not found in view-entity [${this.internalEntityName}]")
                EntityDefinition aliasEntityDef = this.efi.getEntityDefinition((String) memberEntity."@entity-name")
                field = new ConditionField((String) econdition."@entity-alias", (String) econdition."@field-name", aliasEntityDef)
            } else {
                field = new ConditionField((String) econdition."@field-name")
            }
            if (econdition."@value" != null) {
                // NOTE: may need to convert value from String to object for field
                String condValue = econdition."@value" ?: null
                // NOTE: only expand if contains "${", expanding normal strings does l10n and messes up key values; hopefully this won't result in a similar issue
                if (condValue && condValue.contains("\${")) condValue = efi.getEcfi().getResourceFacade().expand(condValue, "")
                cond = new FieldValueCondition((EntityConditionFactoryImpl) this.efi.conditionFactory, field,
                        EntityConditionFactoryImpl.getComparisonOperator((String) econdition."@operator"), condValue)
            } else {
                ConditionField toField
                if (econdition."@to-entity-alias") {
                    Node memberEntity = (Node) this.internalEntityNode."member-entity".find({ it."@entity-alias" == econdition."@to-entity-alias"})
                    if (!memberEntity) throw new EntityException("The entity-alias [${econdition."@to-entity-alias"}] was not found in view-entity [${this.internalEntityName}]")
                    EntityDefinition aliasEntityDef = this.efi.getEntityDefinition((String) memberEntity."@entity-name")
                    toField = new ConditionField((String) econdition."@to-entity-alias", (String) econdition."@to-field-name", aliasEntityDef)
                } else {
                    toField = new ConditionField((String) econdition."@to-field-name")
                }
                cond = new FieldToFieldCondition((EntityConditionFactoryImpl) this.efi.conditionFactory, field,
                        EntityConditionFactoryImpl.getComparisonOperator((String) econdition."@operator"), toField)
            }
            if (cond && econdition."@ignore-case" == "true") cond.ignoreCase()

            if (cond && econdition."@or-null" == "true") {
                cond = (EntityConditionImplBase) this.efi.conditionFactory.makeCondition(cond, JoinOperator.OR,
                        new FieldValueCondition((EntityConditionFactoryImpl) this.efi.conditionFactory, field, EntityCondition.EQUALS, null))
            }

            if (cond) condList.add(cond)
        }
        for (Node econditions in conditionsParent."econditions") {
            EntityConditionImplBase cond = this.makeViewListCondition(econditions)
            if (cond) condList.add(cond)
        }
        if (!condList) return null
        if (condList.size() == 1) return (EntityConditionImplBase) condList.get(0)
        JoinOperator op = (conditionsParent."@combine" == "or" ? JoinOperator.OR : JoinOperator.AND)
        EntityConditionImplBase entityCondition = (EntityConditionImplBase) this.efi.conditionFactory.makeCondition((List<EntityCondition>) condList, op)
        // logger.info("============== In makeViewListCondition for entity [${entityName}] resulting entityCondition: ${entityCondition}")
        return entityCondition
    }

    EntityConditionImplBase makeViewHavingCondition() {
        if (!this.isViewEntity()) return null
        // add the view-entity.entity-condition.having-econditions
        Node havingEconditions = (Node) this.internalEntityNode."entity-condition"?.getAt(0)?."having-econditions"?.getAt(0)
        if (!havingEconditions) return null
        return makeViewListCondition(havingEconditions)
    }

    @Override
    int hashCode() {
        return this.internalEntityName.hashCode()
    }

    @Override
    boolean equals(Object o) {
        if (o == null || o.getClass() != this.getClass()) return false
        EntityDefinition that = (EntityDefinition) o
        if (!this.internalEntityName.equals(that.internalEntityName)) return false
        return true
    }

    protected static Map<String, String> camelToUnderscoreMap = new HashMap()
    @CompileStatic
    static String camelCaseToUnderscored(String camelCase) {
        if (!camelCase) return ""
        String usv = camelToUnderscoreMap.get(camelCase)
        if (usv) return usv

        StringBuilder underscored = new StringBuilder()
        underscored.append(Character.toUpperCase(camelCase.charAt(0)))
        int inPos = 1
        while (inPos < camelCase.length()) {
            char curChar = camelCase.charAt(inPos)
            if (Character.isUpperCase(curChar)) underscored.append('_')
            underscored.append(Character.toUpperCase(curChar))
            inPos++
        }

        usv = underscored.toString()
        camelToUnderscoreMap.put(camelCase, usv)
        return usv
    }
}
