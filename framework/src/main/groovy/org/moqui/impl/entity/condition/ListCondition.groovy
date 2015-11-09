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
import org.moqui.impl.entity.EntityConditionFactoryImpl
import org.moqui.impl.entity.EntityQueryBuilder
import org.moqui.entity.EntityCondition

@CompileStatic
class ListCondition extends EntityConditionImplBase {
    protected List<EntityConditionImplBase> conditionList
    protected EntityCondition.JoinOperator operator
    protected int curHashCode
    protected static final Class thisClass = ListCondition.class

    ListCondition(EntityConditionFactoryImpl ecFactoryImpl,
            List<EntityConditionImplBase> conditionList, EntityCondition.JoinOperator operator) {
        super(ecFactoryImpl)
        if (conditionList) {
            Iterator<EntityConditionImplBase> conditionIter = conditionList.iterator()
            while (conditionIter.hasNext()) if (conditionIter.next() == null) conditionIter.remove()
        }
        this.conditionList = conditionList ?: new LinkedList<EntityConditionImplBase>()
        this.operator = operator ?: AND
        curHashCode = createHashCode()
    }

    void addCondition(EntityConditionImplBase condition) {
        if (condition != null) conditionList.add(condition)
        curHashCode = createHashCode()
    }

    EntityCondition.JoinOperator getOperator() { return operator }

    @Override
    void makeSqlWhere(EntityQueryBuilder eqb) {
        if (!this.conditionList) return

        StringBuilder sql = eqb.getSqlTopLevel()
        sql.append('(')
        boolean isFirst = true
        for (EntityConditionImplBase condition in this.conditionList) {
            if (isFirst) isFirst = false else {
                sql.append(' ').append(EntityConditionFactoryImpl.getJoinOperatorString(this.operator)).append(' ')
            }
            condition.makeSqlWhere(eqb)
        }
        sql.append(')')
    }

    @Override
    boolean mapMatches(Map<String, ?> map) {
        for (EntityConditionImplBase condition in this.conditionList) {
            boolean conditionMatches = condition.mapMatches(map)
            if (conditionMatches && this.operator == OR) return true
            if (!conditionMatches && this.operator == AND) return false
        }
        // if we got here it means that it's an OR with no trues, or an AND with no falses
        return (this.operator == AND)
    }

    @Override
    boolean populateMap(Map<String, ?> map) {
        if (operator != AND) return false
        for (EntityConditionImplBase condition in this.conditionList) if (!condition.populateMap(map)) return false
        return true
    }

    void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        for (EntityConditionImplBase cond in conditionList) cond.getAllAliases(entityAliasSet, fieldAliasSet)
    }

    @Override
    EntityCondition ignoreCase() { throw new IllegalArgumentException("Ignore case not supported for this type of condition.") }

    @Override
    String toString() {
        StringBuilder sb = new StringBuilder()
        for (EntityConditionImplBase condition in this.conditionList) {
            if (sb.length() > 0) sb.append(' ').append(EntityConditionFactoryImpl.getJoinOperatorString(this.operator)).append(' ')
            sb.append('(').append(condition.toString()).append(')')
        }
        return sb.toString()
    }

    @Override
    int hashCode() { return curHashCode }
    protected int createHashCode() {
        return (conditionList ? conditionList.hashCode() : 0) + operator.hashCode()
    }

    @Override
    boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false
        ListCondition that = (ListCondition) o
        // NOTE: for Java Enums the != is WAY faster than the .equals
        if (this.operator != that.operator) return false
        if (!this.conditionList.equals(that.conditionList)) return false
        return true
    }
}
