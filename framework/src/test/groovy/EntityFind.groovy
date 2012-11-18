/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by Paul Z. Xu, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */

import spock.lang.*

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.moqui.Moqui
import java.sql.Timestamp
import org.moqui.entity.EntityCondition

class EntityFind extends Specification {
    @Shared
    ExecutionContext ec
    @Shared
    Timestamp timestamp

    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
        timestamp = ec.user.nowTimestamp
    }

    def cleanupSpec() {
        ec.destroy()
    }

    def setup() {
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)
        ec.entity.makeValue("Example").setAll([exampleId:"TEST1", exampleTypeEnumId: null,
                description: "", exampleName:"Test Name",
                exampleSize: 100, exampleDate: timestamp]).createOrUpdate()
    }

    def cleanup() {
        ec.entity.makeValue("Example").set("exampleId", "TEST1").delete()
        ec.artifactExecution.enableAuthz()
        ec.transaction.commit()
    }

    @Unroll
    def "find Example by single condition (#fieldName = #value)"() {
        expect:
        EntityValue example = ec.entity.makeFind("Example").condition(fieldName, value).one()
        example != null
        example.exampleId == "TEST1"

        where:
        fieldName | value
        "exampleId" | "TEST1"
        "exampleSize" | "100"
        "exampleSize" | 100
        "exampleDate" | ec.l10n.formatValue(timestamp, "yyyy-MM-dd HH:mm:ss.SSS")
        "exampleDate" | timestamp
    }

    @Unroll
    def "find Example by operator condition (#fieldName #operator #value)"() {
        expect:
        EntityValue example = ec.entity.makeFind("Example").condition(fieldName, operator, value).one()
        example != null
        example.exampleId == "TEST1"

        where:
        fieldName | operator | value
        "exampleId" | EntityCondition.BETWEEN | ["TEST0", "TEST2"]
        "exampleId" | EntityCondition.EQUALS | "TEST1"
        "exampleId" | EntityCondition.IN | ["TEST1"]
        "exampleId" | EntityCondition.LIKE | "%TEST1%"
    }

    @Unroll
    def "find Example by searchFormInputs (#inputsMap)"() {
        expect:
        ec.context.putAll(inputsMap)
        EntityValue example = ec.entity.makeFind("Example").searchFormInputs("", "", false).one()
        resultId ? example != null && example.exampleId == resultId : example == null

        where:
        inputsMap | resultId
        [exampleId: "TEST1", exampleId_op: "equals"] | "TEST1"
        [exampleId: "%TEST1%", exampleId_op: "like"] | "TEST1"
        [exampleId: "TEST1", exampleId_op: "contains"] | "TEST1"
        [exampleId: "TEST1", exampleId_op: "equals", exampleTypeEnumId_op: "empty"] | "TEST1"
        [exampleId: "TEST1", exampleId_op: "equals", description_op: "empty"] | "TEST1"
        [exampleId: "TEST1", exampleId_op: "equals", exampleDate_from: "", exampleDate_thru: ""] | "TEST1"
        [exampleId: "TEST1", exampleId_op: "equals", exampleDate_from: timestamp, exampleDate_thru: timestamp] | null
        [exampleId: "TEST1", exampleId_op: "equals", exampleDate_from: timestamp, exampleDate_thru: timestamp + 1] | "TEST1"
        [exampleId: "TEST1", exampleId_op: "equals", exampleName_not: "Y", exampleName_op: "equals", exampleName: ""] | "TEST1"
        [exampleId: "TEST1", exampleId_op: "equals", exampleName_not: "Y", exampleName_op: "empty"] | "TEST1"
    }
}
