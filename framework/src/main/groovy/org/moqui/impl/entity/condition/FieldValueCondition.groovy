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
package org.moqui.impl.entity.condition

import groovy.transform.CompileStatic
import org.moqui.entity.EntityCondition
import org.moqui.impl.entity.EntityQueryBuilder.EntityConditionParameter
import org.moqui.impl.entity.EntityConditionFactoryImpl
import org.moqui.impl.entity.EntityQueryBuilder

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class FieldValueCondition extends EntityConditionImplBase {
    protected final static Logger logger = LoggerFactory.getLogger(FieldValueCondition.class)

    protected ConditionField field
    protected EntityCondition.ComparisonOperator operator
    protected Object value
    protected Boolean ignoreCase = false
    protected int curHashCode
    protected static final Class thisClass = FieldValueCondition.class

    FieldValueCondition(EntityConditionFactoryImpl ecFactoryImpl,
            ConditionField field, EntityCondition.ComparisonOperator operator, Object value) {
        super(ecFactoryImpl)
        this.field = field
        this.operator = operator ?: EQUALS
        this.value = value
        curHashCode = createHashCode()
    }

    @Override
    void makeSqlWhere(EntityQueryBuilder eqb) {
        StringBuilder sql = eqb.getSqlTopLevel()
        if (this.ignoreCase) sql.append("UPPER(")
        sql.append(field.getColumnName(eqb.getMainEd()))
        if (this.ignoreCase) sql.append(')')
        sql.append(' ')
        boolean valueDone = false
        if (this.value == null) {
            if (this.operator == EQUALS || this.operator == LIKE || this.operator == IN || this.operator == BETWEEN) {
                sql.append(" IS NULL")
                valueDone = true
            } else if (this.operator == NOT_EQUAL || this.operator == NOT_LIKE || this.operator == NOT_IN || this.operator == NOT_BETWEEN) {
                sql.append(" IS NOT NULL")
                valueDone = true
            }
        }
        if (this.operator == IS_NULL || this.operator == IS_NOT_NULL) {
            sql.append(EntityConditionFactoryImpl.getComparisonOperatorString(this.operator))
            valueDone = true
        }
        if (!valueDone) {
            sql.append(EntityConditionFactoryImpl.getComparisonOperatorString(this.operator))
            if (this.operator == IN || this.operator == NOT_IN) {
                if (this.value instanceof CharSequence) {
                    String valueStr = this.value.toString()
                    if (valueStr.contains(",")) this.value = valueStr.split(",").collect()
                }
                if (this.value instanceof Collection) {
                    sql.append(" (")
                    boolean isFirst = true
                    for (Object curValue in this.value) {
                        if (isFirst) isFirst = false else sql.append(", ")
                        sql.append("?")
                        if (this.ignoreCase && (curValue instanceof CharSequence)) curValue = curValue.toString().toUpperCase()
                        eqb.getParameters().add(new EntityConditionParameter(field.getFieldInfo(eqb.mainEntityDefinition), curValue, eqb))
                    }
                    sql.append(')')
                } else {
                    if (this.ignoreCase && (this.value instanceof CharSequence)) this.value = this.value.toString().toUpperCase()
                    sql.append(" (?)")
                    eqb.getParameters().add(new EntityConditionParameter(field.getFieldInfo(eqb.mainEntityDefinition), this.value, eqb))
                }
            } else if ((this.operator == BETWEEN || this.operator == NOT_BETWEEN) && this.value instanceof Collection &&
                    ((Collection) this.value).size() == 2) {
                sql.append(" ? AND ?")
                Iterator iterator = ((Collection) this.value).iterator()
                Object value1 = iterator.next()
                if (this.ignoreCase && (value1 instanceof CharSequence)) value1 = value1.toString().toUpperCase()
                Object value2 = iterator.next()
                if (this.ignoreCase && (value2 instanceof CharSequence)) value2 = value2.toString().toUpperCase()
                eqb.getParameters().add(new EntityConditionParameter(field.getFieldInfo(eqb.mainEntityDefinition), value1, eqb))
                eqb.getParameters().add(new EntityConditionParameter(field.getFieldInfo(eqb.mainEntityDefinition), value2, eqb))
            } else {
                if (this.ignoreCase && (this.value instanceof CharSequence)) this.value = this.value.toString().toUpperCase()
                sql.append(" ?")
                eqb.getParameters().add(new EntityConditionParameter(field.getFieldInfo(eqb.mainEntityDefinition), this.value, eqb))
            }
        }
    }

    @Override
    boolean mapMatches(Map<String, ?> map) { return EntityConditionFactoryImpl.compareByOperator(map.get(field.fieldName), operator, value) }

    @Override
    boolean populateMap(Map<String, ?> map) {
        if (operator != EQUALS || ignoreCase || field.entityAlias) return false
        map.put(field.fieldName, value)
        return true
    }

    void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        // this will only be called for view-entity, so we'll either have a entityAlias or an aliased fieldName
        if (field.entityAlias) {
            entityAliasSet.add(field.entityAlias)
        } else {
            fieldAliasSet.add(field.fieldName)
        }
    }

    @Override
    EntityCondition ignoreCase() { this.ignoreCase = true; curHashCode = createHashCode(); return this }

    @Override
    String toString() {
        return (field as String) + " " + EntityConditionFactoryImpl.getComparisonOperatorString(this.operator) + " " + (value as String)
    }

    @Override
    int hashCode() { return curHashCode }
    protected int createHashCode() {
        return (field ? field.hashCode() : 0) + operator.hashCode() + (value ? value.hashCode() : 0) + ignoreCase.hashCode()
    }

    @Override
    boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false
        FieldValueCondition that = (FieldValueCondition) o
        if (!field.equalsConditionField(that.field)) return false
        if (value == null && that.value != null) return false
        if (value != null) {
            if (that.value == null) {
                return false
            } else {
                if (!value.equals(that.value)) return false
            }
        }
        if (operator != that.operator) return false
        if (ignoreCase != that.ignoreCase) return false
        return true
    }
}
