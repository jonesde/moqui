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

import java.sql.Timestamp

import org.moqui.entity.EntityConditionFactory
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityCondition.JoinOperator
import org.moqui.entity.EntityCondition.ComparisonOperator
import org.moqui.impl.entity.condition.EntityConditionImplBase
import org.moqui.impl.entity.condition.BasicJoinCondition
import org.moqui.impl.entity.condition.FieldValueCondition
import org.moqui.impl.entity.condition.ConditionField
import org.moqui.impl.entity.condition.DateCondition
import org.moqui.impl.entity.condition.WhereCondition
import org.moqui.impl.entity.condition.FieldToFieldCondition
import org.moqui.impl.entity.condition.ListCondition
import org.moqui.impl.entity.condition.MapCondition
import org.moqui.impl.StupidUtilities

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class EntityConditionFactoryImpl implements EntityConditionFactory {
    protected final static Logger logger = LoggerFactory.getLogger(EntityConditionFactoryImpl.class)

    protected final EntityFacadeImpl efi

    EntityConditionFactoryImpl(EntityFacadeImpl efi) {
        this.efi = efi
    }

    @Override
    EntityCondition makeCondition(EntityCondition lhs, JoinOperator operator, EntityCondition rhs) {
        return new BasicJoinCondition(this, (EntityConditionImplBase) lhs, operator, (EntityConditionImplBase) rhs)
    }

    @Override
    EntityCondition makeCondition(String fieldName, ComparisonOperator operator, Object value) {
        return new FieldValueCondition(this, new ConditionField(fieldName), operator, value)
    }

    @Override
    EntityCondition makeConditionToField(String fieldName, ComparisonOperator operator, String toFieldName) {
        return new FieldToFieldCondition(this, new ConditionField(fieldName), operator, new ConditionField(toFieldName))
    }

    @Override
    EntityCondition makeCondition(List<EntityCondition> conditionList) {
        return this.makeCondition(conditionList, JoinOperator.AND)
    }
    @Override
    EntityCondition makeCondition(List<EntityCondition> conditionList, JoinOperator operator) {
        if (!conditionList) return null
        List<EntityConditionImplBase> newList = []
        Iterator<EntityCondition> conditionIter = conditionList.iterator()
        while (conditionIter.hasNext()) {
            EntityCondition curCond = conditionIter.next()
            if (curCond == null) continue
            // this is all they could be, all that is supported right now
            if (curCond instanceof EntityConditionImplBase) newList.add((EntityConditionImplBase) curCond)
            else throw new IllegalArgumentException("EntityCondition of type [${curCond.getClass().getName()}] not supported")
        }
        if (!newList) return null
        if (newList.size() == 1) {
            return newList.get(0)
        } else {
            return new ListCondition(this, newList, operator)
        }
    }

    @Override
    EntityCondition makeCondition(List<Object> conditionList, String listOperator, String mapComparisonOperator, String mapJoinOperator) {
        if (!conditionList) return null

        JoinOperator listJoin = listOperator ? getJoinOperator(listOperator) : JoinOperator.AND
        ComparisonOperator mapComparison = mapComparisonOperator ? getComparisonOperator(mapComparisonOperator) : ComparisonOperator.EQUALS
        JoinOperator mapJoin = mapJoinOperator ? getJoinOperator(mapJoinOperator) : JoinOperator.AND

        List<EntityConditionImplBase> newList = []
        Iterator<Object> conditionIter = conditionList.iterator()
        while (conditionIter.hasNext()) {
            Object curObj = conditionIter.next()
            if (curObj == null) continue
            if (curObj instanceof Map) {
                Map curMap = (Map) curObj
                if (curMap.size() == 0) continue
                EntityCondition curCond = makeCondition(curMap, mapComparison, mapJoin)
                newList.add((EntityConditionImplBase) curCond)
            } else if (curObj instanceof EntityConditionImplBase) {
                EntityCondition curCond = (EntityConditionImplBase) curObj
                newList.add(curCond)
            } else {
                throw new IllegalArgumentException("The conditionList parameter must contain only Map and EntityCondition objects, found entry of type [${curObj.getClass().getName()}]")
            }
        }
        if (!newList) return null
        if (newList.size() == 1) {
            return newList.get(0)
        } else {
            return new ListCondition(this, newList, listJoin)
        }
    }

    @Override
    EntityCondition makeCondition(Map<String, Object> fieldMap, ComparisonOperator comparisonOperator, JoinOperator joinOperator) {
        if (!fieldMap) return null
        return new MapCondition(this, fieldMap, comparisonOperator, joinOperator)
    }

    @Override
    EntityCondition makeCondition(Map<String, Object> fieldMap) {
        if (!fieldMap) return null
        return new MapCondition(this, fieldMap, ComparisonOperator.EQUALS, JoinOperator.AND)
    }

    @Override
    EntityCondition makeConditionDate(String fromFieldName, String thruFieldName, Timestamp compareStamp) {
        return new DateCondition(this, fromFieldName, thruFieldName,
                compareStamp ?: efi.getEcfi().getExecutionContext().getUser().getNowTimestamp())
    }
    EntityCondition makeConditionDate(String fromFieldName, String thruFieldName, Timestamp compareStamp, boolean ignoreIfEmpty) {
        if (ignoreIfEmpty && (Object) compareStamp == null) return null
        return new DateCondition(this, fromFieldName, thruFieldName,
                compareStamp ?: efi.getEcfi().getExecutionContext().getUser().getNowTimestamp())
    }

    @Override
    EntityCondition makeConditionWhere(String sqlWhereClause) {
        if (!sqlWhereClause) return null
        return new WhereCondition(this, sqlWhereClause)
    }

    ComparisonOperator comparisonOperatorFromEnumId(String enumId) {
        switch (enumId) {
            case "ENTCO_LESS": return EntityCondition.LESS_THAN
            case "ENTCO_GREATER": return EntityCondition.GREATER_THAN
            case "ENTCO_LESS_EQ": return EntityCondition.LESS_THAN_EQUAL_TO
            case "ENTCO_GREATER_EQ": return EntityCondition.GREATER_THAN_EQUAL_TO
            case "ENTCO_EQUALS": return EntityCondition.EQUALS
            case "ENTCO_NOT_EQUALS": return EntityCondition.NOT_EQUAL
            case "ENTCO_IN": return EntityCondition.IN
            case "ENTCO_NOT_IN": return EntityCondition.NOT_IN
            case "ENTCO_BETWEEN": return EntityCondition.BETWEEN
            case "ENTCO_NOT_BETWEEN": return EntityCondition.NOT_BETWEEN
            case "ENTCO_LIKE": return EntityCondition.LIKE
            case "ENTCO_NOT_LIKE": return EntityCondition.NOT_LIKE
            case "ENTCO_IS_NULL": return EntityCondition.IS_NULL
            case "ENTCO_IS_NOT_NULL": return EntityCondition.IS_NOT_NULL
            default: return null
        }
    }

    EntityCondition makeActionCondition(String fieldName, String operator, String fromExpr, String value, String toFieldName, boolean ignoreCase, boolean ignoreIfEmpty, boolean orNull, String ignore) {
        Object from = fromExpr ? this.efi.ecfi.resourceFacade.expression(fromExpr, "") : null
        return makeActionConditionDirect(fieldName, operator, from, value, toFieldName, ignoreCase, ignoreIfEmpty, orNull, ignore)
    }
    EntityCondition makeActionConditionDirect(String fieldName, String operator, Object fromObj, String value, String toFieldName, boolean ignoreCase, boolean ignoreIfEmpty, boolean orNull, String ignore) {
        // logger.info("TOREMOVE makeActionCondition(fieldName ${fieldName}, operator ${operator}, fromExpr ${fromExpr}, value ${value}, toFieldName ${toFieldName}, ignoreCase ${ignoreCase}, ignoreIfEmpty ${ignoreIfEmpty}, orNull ${orNull}, ignore ${ignore})")

        if (efi.getEcfi().getResourceFacade().condition(ignore, null)) return null

        if (toFieldName) {
            EntityCondition ec = makeConditionToField(fieldName, getComparisonOperator(operator), toFieldName)
            if (ignoreCase) ec.ignoreCase()
            return ec
        } else {
            Object condValue
            if (value) {
                // NOTE: have to convert value (if needed) later on because we don't know which entity/field this is for, or change to pass in entity?
                condValue = value
            } else {
                condValue = fromObj
            }
            if (ignoreIfEmpty && !condValue) return null

            EntityCondition mainEc = makeCondition(fieldName, getComparisonOperator(operator), condValue)
            if (ignoreCase) mainEc.ignoreCase()

            EntityCondition ec = mainEc
            if (orNull) ec = makeCondition(mainEc, JoinOperator.OR, makeCondition(fieldName, ComparisonOperator.EQUALS, null))
            return ec
        }
    }

    EntityCondition makeActionCondition(Node node) {
        Map attrs = node.attributes()
        return makeActionCondition((String) attrs.get("field-name"),
                (String) attrs.get("operator") ?: "equals", (String) (attrs.get("from") ?: attrs.get("field-name")),
                (String) attrs.get("value"), (String) attrs.get("to-field-name"), (attrs.get("ignore-case") ?: "false") == "true",
                (attrs.get("ignore-if-empty") ?: "false") == "true", (attrs.get("or-null") ?: "false") == "true",
                ((String) attrs.get("ignore") ?: "false"))
    }

    EntityCondition makeActionConditions(Node node) {
        List<EntityCondition> condList = new ArrayList()
        List<Node> nodeChildren = (List<Node>) node.children()
        for (Node subCond in nodeChildren) condList.add(makeActionCondition(subCond))
        return makeCondition(condList, getJoinOperator((String) node.attribute("combine")))
    }

    protected static final Map<ComparisonOperator, String> comparisonOperatorStringMap = new HashMap()
    static {
        comparisonOperatorStringMap.put(ComparisonOperator.EQUALS, "=")
        comparisonOperatorStringMap.put(ComparisonOperator.NOT_EQUAL, "<>")
        comparisonOperatorStringMap.put(ComparisonOperator.LESS_THAN, "<")
        comparisonOperatorStringMap.put(ComparisonOperator.GREATER_THAN, ">")
        comparisonOperatorStringMap.put(ComparisonOperator.LESS_THAN_EQUAL_TO, "<=")
        comparisonOperatorStringMap.put(ComparisonOperator.GREATER_THAN_EQUAL_TO, ">=")
        comparisonOperatorStringMap.put(ComparisonOperator.IN, "IN")
        comparisonOperatorStringMap.put(ComparisonOperator.NOT_IN, "NOT IN")
        comparisonOperatorStringMap.put(ComparisonOperator.BETWEEN, "BETWEEN")
        comparisonOperatorStringMap.put(ComparisonOperator.NOT_BETWEEN, "NOT BETWEEN")
        comparisonOperatorStringMap.put(ComparisonOperator.LIKE, "LIKE")
        comparisonOperatorStringMap.put(ComparisonOperator.NOT_LIKE, "NOT LIKE")
        comparisonOperatorStringMap.put(ComparisonOperator.IS_NULL, "IS NULL")
        comparisonOperatorStringMap.put(ComparisonOperator.IS_NOT_NULL, "IS NOT NULL")
    }
    protected static final Map<String, ComparisonOperator> stringComparisonOperatorMap = [
            "=":ComparisonOperator.EQUALS,
            "equals":ComparisonOperator.EQUALS,

            "not-equals":ComparisonOperator.NOT_EQUAL,
            "not-equal":ComparisonOperator.NOT_EQUAL,
            "!=":ComparisonOperator.NOT_EQUAL,
            "<>":ComparisonOperator.NOT_EQUAL,

            "less-than":ComparisonOperator.LESS_THAN,
            "less":ComparisonOperator.LESS_THAN,
            "<":ComparisonOperator.LESS_THAN,

            "greater-than":ComparisonOperator.GREATER_THAN,
            "greater":ComparisonOperator.GREATER_THAN,
            ">":ComparisonOperator.GREATER_THAN,

            "less-than-equal-to":ComparisonOperator.LESS_THAN_EQUAL_TO,
            "less-equals":ComparisonOperator.LESS_THAN_EQUAL_TO,
            "<=":ComparisonOperator.LESS_THAN_EQUAL_TO,

            "greater-than-equal-to":ComparisonOperator.GREATER_THAN_EQUAL_TO,
            "greater-equals":ComparisonOperator.GREATER_THAN_EQUAL_TO,
            ">=":ComparisonOperator.GREATER_THAN_EQUAL_TO,

            "in":ComparisonOperator.IN,
            "IN":ComparisonOperator.IN,

            "not-in":ComparisonOperator.NOT_IN,
            "NOT IN":ComparisonOperator.NOT_IN,

            "between":ComparisonOperator.BETWEEN,
            "BETWEEN":ComparisonOperator.BETWEEN,

            "not-between":ComparisonOperator.NOT_BETWEEN,
            "NOT BETWEEN":ComparisonOperator.NOT_BETWEEN,

            "like":ComparisonOperator.LIKE,
            "LIKE":ComparisonOperator.LIKE,

            "not-like":ComparisonOperator.NOT_LIKE,
            "NOT LIKE":ComparisonOperator.NOT_LIKE,

            "is-null":ComparisonOperator.IS_NULL,
            "IS NULL":ComparisonOperator.IS_NULL,

            "is-not-null":ComparisonOperator.IS_NOT_NULL,
            "IS NOT NULL":ComparisonOperator.IS_NOT_NULL
    ]

    static String getJoinOperatorString(JoinOperator op) {
        return op == JoinOperator.OR ? "OR" : "AND"
    }
    static JoinOperator getJoinOperator(String opName) {
        if (!opName) return JoinOperator.AND
        switch (opName) {
            case "or":
            case "OR": return JoinOperator.OR
            case "and":
            case "AND":
            default: return JoinOperator.AND
        }
    }
    static String getComparisonOperatorString(ComparisonOperator op) {
        return comparisonOperatorStringMap.get(op)
    }
    static ComparisonOperator getComparisonOperator(String opName) {
        return stringComparisonOperatorMap.get(opName)
    }

    static boolean compareByOperator(Object value1, ComparisonOperator op, Object value2) {
        switch (op) {
        case ComparisonOperator.EQUALS:
            return value1 == value2
        case ComparisonOperator.NOT_EQUAL:
            return value1 != value2
        case ComparisonOperator.LESS_THAN:
            Comparable comp1 = StupidUtilities.makeComparable(value1)
            Comparable comp2 = StupidUtilities.makeComparable(value2)
            return comp1 < comp2
        case ComparisonOperator.GREATER_THAN:
            Comparable comp1 = StupidUtilities.makeComparable(value1)
            Comparable comp2 = StupidUtilities.makeComparable(value2)
            return comp1 > comp2
        case ComparisonOperator.LESS_THAN_EQUAL_TO:
            Comparable comp1 = StupidUtilities.makeComparable(value1)
            Comparable comp2 = StupidUtilities.makeComparable(value2)
            return comp1 <= comp2
        case ComparisonOperator.GREATER_THAN_EQUAL_TO:
            Comparable comp1 = StupidUtilities.makeComparable(value1)
            Comparable comp2 = StupidUtilities.makeComparable(value2)
            return comp1 >= comp2
        case ComparisonOperator.IN:
            if (value2 instanceof Collection) {
                return ((Collection) value2).contains(value1)
            } else {
                // not a Collection, try equals
                return value1 == value2
            }
        case ComparisonOperator.NOT_IN:
            if (value2 instanceof Collection) {
                return !((Collection) value2).contains(value1)
            } else {
                // not a Collection, try not-equals
                return value1 != value2
            }
        case ComparisonOperator.BETWEEN:
            if (value2 instanceof Collection && ((Collection) value2).size() == 2) {
                Comparable comp1 = StupidUtilities.makeComparable(value1)
                Iterator iterator = ((Collection) value2).iterator()
                Comparable lowObj = StupidUtilities.makeComparable(iterator.next())
                Comparable highObj = StupidUtilities.makeComparable(iterator.next())
                return lowObj <= comp1 && comp1 < highObj
            } else {
                return false
            }
        case ComparisonOperator.NOT_BETWEEN:
            if (value2 instanceof Collection && ((Collection) value2).size() == 2) {
                Comparable comp1 = StupidUtilities.makeComparable(value1)
                Iterator iterator = ((Collection) value2).iterator()
                Comparable lowObj = StupidUtilities.makeComparable(iterator.next())
                Comparable highObj = StupidUtilities.makeComparable(iterator.next())
                return lowObj > comp1 && comp1 >= highObj
            } else {
                return false
            }
        case ComparisonOperator.LIKE:
            return StupidUtilities.compareLike(value1, value2)
        case ComparisonOperator.NOT_LIKE:
            return !StupidUtilities.compareLike(value1, value2)
        case ComparisonOperator.IS_NULL:
            return value2 == null
        case ComparisonOperator.IS_NOT_NULL:
            return value2 != null
        }
        // default return false
        return false
    }
}
