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

import org.moqui.entity.EntityCondition
import org.moqui.impl.entity.EntityQueryBuilder
import org.moqui.impl.entity.EntityConditionFactoryImpl

class FieldToFieldCondition extends EntityConditionImplBase {
    protected volatile Class localClass = null
    protected ConditionField field
    protected EntityCondition.ComparisonOperator operator
    protected ConditionField toField
    protected boolean ignoreCase = false

    FieldToFieldCondition(EntityConditionFactoryImpl ecFactoryImpl,
            ConditionField field, EntityCondition.ComparisonOperator operator, ConditionField toField) {
        super(ecFactoryImpl)
        this.field = field
        this.operator = operator ?: EQUALS
        this.toField = toField
    }

    Class getLocalClass() { if (this.localClass == null) this.localClass = this.getClass(); return this.localClass }

    @Override
    void makeSqlWhere(EntityQueryBuilder eqb) {
        StringBuilder sql = eqb.getSqlTopLevel()
        if (this.ignoreCase) sql.append("UPPER(")
        sql.append(field.getColumnName(eqb.getMainEd()))
        if (this.ignoreCase) sql.append(")")
        sql.append(' ')
        sql.append(EntityConditionFactoryImpl.getComparisonOperatorString(this.operator))
        sql.append(' ')
        if (this.ignoreCase) sql.append("UPPER(")
        sql.append(toField.getColumnName(eqb.getMainEd()))
        if (this.ignoreCase) sql.append(")")
    }

    @Override
    boolean mapMatches(Map<String, ?> map) {
        return EntityConditionFactoryImpl.compareByOperator(map.get(field.getFieldName()), this.operator, map.get(toField.getFieldName()))
    }

    @Override
    boolean populateMap(Map<String, ?> map) { return false }

    void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        // this will only be called for view-entity, so we'll either have a entityAlias or an aliased fieldName
        if (field.entityAlias) {
            entityAliasSet.add(field.entityAlias)
        } else {
            fieldAliasSet.add(field.fieldName)
        }
        if (toField.entityAlias) {
            entityAliasSet.add(toField.entityAlias)
        } else {
            fieldAliasSet.add(toField.fieldName)
        }
    }

    @Override
    EntityCondition ignoreCase() { this.ignoreCase = true; return this }

    @Override
    String toString() {
        return field + " " + EntityConditionFactoryImpl.getComparisonOperatorString(this.operator) + " " + toField
    }

    @Override
    int hashCode() {
        return (field ? field.hashCode() : 0) + operator.hashCode() + (toField ? toField.hashCode() : 0) + ignoreCase.hashCode()
    }

    @Override
    boolean equals(Object o) {
        if (o == null || o.getClass() != this.getLocalClass()) return false
        FieldToFieldCondition that = (FieldToFieldCondition) o
        if (!this.field.equalsConditionField(that.field)) return false
        // NOTE: for Java Enums the != is WAY faster than the .equals
        if (this.operator != that.operator) return false
        if (!this.toField.equalsConditionField(that.toField)) return false
        if (this.ignoreCase != that.ignoreCase) return false
        return true
    }
}
