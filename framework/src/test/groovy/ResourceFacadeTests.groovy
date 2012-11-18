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

import spock.lang.*

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.moqui.Moqui
import org.moqui.context.ResourceReference

class ResourceFacadeTests extends Specification {
    @Shared
    ExecutionContext ec

    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
    }

    def cleanupSpec() {
        ec.destroy()
    }

    @Unroll
    def "get Location ResourceReference (#location)"() {
        expect:
        ResourceReference rr = ec.resource.getLocationReference(location)
        // the resolved location is unpredictable, so don't test for that: rr.location == location
        rr.uri.scheme == scheme
        rr.uri.host == host
        rr.fileName == fileName
        rr.contentType == contentType
        (!rr.supportsExists() || rr.exists) == exists
        (!rr.supportsDirectory() || rr.file) == isFile
        (!rr.supportsDirectory() || rr.directory) == isDirectory

        where:
        location | scheme | host | fileName | contentType | exists | isFile | isDirectory
        "component://example/screen/ExampleApp.xml" | "file" | null | "ExampleApp.xml" | "text/xml" | true | true | false
        "component://example/screen/ExampleAppFoo.xml" | "file" | null | "ExampleAppFoo.xml" | "text/xml" | false | false | false
        "classpath://entity/BasicEntities.xml" | "file" | null | "BasicEntities.xml" | "text/xml" | true | true | false
        "classpath://jta.properties" | "file" | null | "jta.properties" | "text/x-java-properties" | true | true | false
        "classpath://shiro.ini" | "file" | null | "shiro.ini" | "text/plain" | true | true | false
        "template/screen-macro/ScreenHtmlMacros.ftl" | "file" | null | "ScreenHtmlMacros.ftl" | "text/x-freemarker" | true | true | false
        "template/screen-macro" | "file" | null | "" | null | true | false | true
    }

    @Unroll
    def "get Location Text (#location)"() {
        expect:
        String text = ec.resource.getLocationText(location, true)
        text.contains(contents)

        where:
        location | contents
        "component://example/screen/ExampleApp.xml" | "<subscreens default-item=\"Example\">"
        "classpath://shiro.ini" | "org.moqui.impl.MoquiShiroRealm"
    }

    // TODO: add tests for renderTemplateInCurrentContext and runScriptInCurrentContext

    @Unroll
    def "groovy evaluate Condition (#expression)"() {
        expect:
        result == ec.resource.evaluateCondition(expression, "")

        where:
        expression | result
        "true" | true
        "false" | false
        "ec.context instanceof org.moqui.impl.context.ContextStack" | true
    }

    @Unroll
    def "groovy evaluate Context Field (#expression)"() {
        expect:
        result == ec.resource.evaluateContextField(expression, "")

        where:
        expression | result
        "ec.tenantId" | ec.tenantId
        "null" | null
        "undefined_variable" | null
    }

    @Unroll
    def "groovy evaluate String Expand (#inputString)"() {
        expect:
        result == ec.resource.evaluateStringExpand(inputString, "")

        where:
        inputString | result
        "Tenant: \${ec.tenantId}" | "Tenant: ${ec.tenantId}"
        "plain string" | "plain string"
    }
}
