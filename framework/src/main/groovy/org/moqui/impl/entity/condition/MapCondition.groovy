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
class MapCondition extends EntityConditionImplBase {
    protected Map<String, Object> fieldMap
    protected EntityCondition.ComparisonOperator comparisonOperator
    protected EntityCondition.JoinOperator joinOperator
    protected Boolean ignoreCase = false
    protected EntityConditionImplBase internalCond = null
    protected int curHashCode
    protected static final Class thisClass = MapCondition.class

    MapCondition(EntityConditionFactoryImpl ecFactoryImpl,
            Map<String, Object> fieldMap, EntityCondition.ComparisonOperator comparisonOperator,
            EntityCondition.JoinOperator joinOperator) {
        super(ecFactoryImpl)
        this.fieldMap = fieldMap ? fieldMap : new HashMap<String, Object>()
        this.comparisonOperator = comparisonOperator ?: EQUALS
        this.joinOperator = joinOperator ?: AND
        curHashCode = createHashCode()
    }

    @Override
    void makeSqlWhere(EntityQueryBuilder eqb) {
        this.makeCondition().makeSqlWhere(eqb)
    }

    @Override
    boolean mapMatches(Map<String, ?> map) {
        // do this directly instead of going through condition, faster
        // return this.makeCondition().mapMatches(map)

        for (Map.Entry<String, ?> fieldEntry in this.fieldMap.entrySet()) {
            boolean conditionMatches = EntityConditionFactoryImpl.compareByOperator(map.get(fieldEntry.getKey()),
                    comparisonOperator, fieldEntry.getValue())
            if (conditionMatches && joinOperator == OR) return true
            if (!conditionMatches && joinOperator == AND) return false
        }

        // if we got here it means that it's an OR with no true, or an AND with no false
        return (joinOperator == AND)
    }

    @Override
    boolean populateMap(Map<String, ?> map) {
        if (joinOperator != AND || comparisonOperator != EQUALS || ignoreCase) return false
        map.putAll(fieldMap)
        return true
    }

    void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        for (Map.Entry<String, Object> entry in fieldMap.entrySet()) fieldAliasSet.add(entry.key)
    }

    @Override
    EntityCondition ignoreCase() { this.ignoreCase = true; curHashCode = createHashCode(); return this }

    @Override
    String toString() {
        return this.makeCondition().toString()
        /* might want to do something like this at some point, but above is probably better for now
        StringBuilder sb = new StringBuilder()
        for (Map.Entry fieldEntry in this.fieldMap.entrySet()) {
            if (sb.length() > 0) {
                sb.append(' ')
                sb.append(EntityConditionFactoryImpl.getJoinOperatorString(this.joinOperator))
                sb.append(' ')
            }
            sb.append(fieldEntry.getKey())
            sb.append(' ')
            sb.append(EntityConditionFactoryImpl.getComparisonOperatorString(this.comparisonOperator))
            sb.append(' ')
            sb.append(fieldEntry.getValue())
        }
        return sb.toString()
        */
    }

    protected EntityConditionImplBase makeCondition() {
        if (internalCond != null) return internalCond

        List conditionList = new LinkedList()
        for (Map.Entry<String, ?> fieldEntry in this.fieldMap.entrySet()) {
            EntityConditionImplBase newCondition = (EntityConditionImplBase) this.ecFactoryImpl.makeCondition(fieldEntry.getKey(),
                    this.comparisonOperator, fieldEntry.getValue())
            if (this.ignoreCase) newCondition.ignoreCase()
            conditionList.add(newCondition)
        }

        internalCond = (EntityConditionImplBase) this.ecFactoryImpl.makeCondition(conditionList, this.joinOperator)
        return internalCond
    }

    @Override
    int hashCode() { return curHashCode }
    protected int createHashCode() {
        return (fieldMap ? fieldMap.hashCode() : 0) + comparisonOperator.hashCode() + joinOperator.hashCode() +
                ignoreCase.hashCode()
    }

    @Override
    boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false
        MapCondition that = (MapCondition) o
        if (!fieldMap.equals(that.fieldMap)) return false
        if (!comparisonOperator.equals(that.comparisonOperator)) return false
        if (!joinOperator.equals(that.joinOperator)) return false
        if (ignoreCase != that.ignoreCase) return false
        return true
    }
}
