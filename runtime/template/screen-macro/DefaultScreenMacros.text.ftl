<#--
This software is in the public domain under CC0 1.0 Universal plus a Grant of Patent License.

To the extent possible under law, the author(s) have dedicated all
copyright and related and neighboring rights to this software to the
public domain worldwide. This software is distributed without any
warranty.

You should have received a copy of the CC0 Public Domain Dedication
along with this software (see the LICENSE.md file). If not, see
<http://creativecommons.org/publicdomain/zero/1.0/>.
-->

<#macro @element></#macro>

<#macro widgets><#recurse></#macro>
<#macro "fail-widgets"><#recurse></#macro>

<#-- ================ Subscreens ================ -->
<#macro "subscreens-menu"></#macro>
<#macro "subscreens-active">${sri.renderSubscreen()}</#macro>
<#macro "subscreens-panel">${sri.renderSubscreen()}</#macro>

<#-- ================ Section ================ -->
<#macro section>${sri.renderSection(.node["@name"])}</#macro>
<#macro "section-iterate">${sri.renderSection(.node["@name"])}</#macro>

<#-- ================ Containers ================ -->
<#macro container>

<#recurse>
</#macro>

<#macro "container-panel">
    <#if .node["panel-header"]?has_content><#recurse .node["panel-header"][0]></#if>
    <#if .node["panel-left"]?has_content><#recurse .node["panel-left"][0]></#if>
    <#recurse .node["panel-center"][0]>
    <#if .node["panel-right"]?has_content><#recurse .node["panel-right"][0]></#if>
    <#if .node["panel-footer"]?has_content><#recurse .node["panel-footer"][0]></#if>
</#macro>

<#macro "container-dialog">${ec.resource.expand(.node["@button-text"], "")} </#macro>

<#-- ==================== Includes ==================== -->
<#macro "include-screen">${sri.renderIncludeScreen(.node["@location"], .node["@share-scope"]?if_exists)}</#macro>

<#-- ============== Tree ============== -->
<#-- TABLED, not to be part of 1.0:
<#macro tree>
</#macro>
<#macro "tree-node">
</#macro>
<#macro "tree-sub-node">
</#macro>
-->

<#-- ============== Render Mode Elements ============== -->
<#macro "render-mode">
<#if .node["text"]?has_content>
    <#list .node["text"] as textNode>
        <#if textNode["@type"]?has_content && textNode["@type"] == sri.getRenderMode()><#assign textToUse = textNode/></#if>
    </#list>
    <#if !textToUse?has_content>
        <#list .node["text"] as textNode><#if !textNode["@type"]?has_content || textNode["@type"] == "any"><#assign textToUse = textNode/></#if></#list>
    </#if>
    <#if textToUse?exists>
        <#if textToUse["@location"]?has_content>
    <#-- NOTE: this still won't encode templates that are rendered to the writer -->
    <#if .node["@encode"]!"false" == "true">${sri.renderText(textToUse["@location"], textToUse["@template"]?if_exists)?html}<#else/>${sri.renderText(textToUse["@location"], textToUse["@template"]?if_exists)}</#if>
        </#if>
        <#assign inlineTemplateSource = textToUse?string/>
        <#if inlineTemplateSource?has_content>
          <#if !textToUse["@template"]?has_content || textToUse["@template"] == "true">
            <#assign inlineTemplate = [inlineTemplateSource, sri.getActiveScreenDef().location + ".render_mode.text"]?interpret>
            <@inlineTemplate/>
          <#else/>
            <#if .node["@encode"]!"false" == "true">${inlineTemplateSource?html}<#else/>${inlineTemplateSource}</#if>
          </#if>
        </#if>
    </#if>
</#if>
</#macro>

<#macro text><#-- do nothing, is used only through "render-mode" --></#macro>

<#-- ================== Standalone Fields ==================== -->
<#macro link>${ec.resource.expand(.node["@text"], "")} </#macro>

<#macro image>${.node["@alt"]!""}</#macro>
<#macro label><#assign labelValue = ec.resource.expand(.node["@text"], "")>${labelValue} </#macro>
<#macro parameter><#-- do nothing, used directly in other elements --></#macro>

<#-- ====================================================== -->
<#-- ======================= Form ========================= -->
<#macro "form-single">
    <#-- Use the formNode assembled based on other settings instead of the straight one from the file: -->
    <#assign formNode = sri.getFtlFormNode(.node["@name"])>
    ${sri.setSingleFormMapInContext(formNode)}
    <#if formNode["field-layout"]?has_content>
        <#assign fieldLayout = formNode["field-layout"][0]>
        <#list formNode["field-layout"][0]?children as layoutNode>
            <#if layoutNode?node_name == "field-ref">
                <#assign fieldRef = layoutNode["@name"]>
                <#assign fieldNode = "invalid">
                <#list formNode["field"] as fn><#if fn["@name"] == fieldRef><#assign fieldNode = fn><#break></#if></#list>
                <#lt><@formSingleSubField fieldNode/>

            <#elseif layoutNode?node_name == "field-row">
                <#list layoutNode["field-ref"] as rowFieldRefNode>
                    <#assign fieldRef = rowFieldRefNode["@name"]>
                    <#assign fieldNode = "invalid">
                    <#list formNode["field"] as fn><#if fn["@name"] == fieldRef><#assign fieldNode = fn><#break></#if></#list>
                    <#t><@formSingleSubField fieldNode/>
                </#list>

            <#elseif layoutNode?node_name == "field-group">
                <#lt>--${layoutNode["@title"]?default("Section " + layoutNode_index)}--
                <#list layoutNode?children as groupNode>
                    <#if groupNode?node_name == "field-ref">
                        <#assign fieldRef = groupNode["@name"]>
                        <#assign fieldNode = "invalid">
                        <#list formNode["field"] as fn><#if fn["@name"] == fieldRef><#assign fieldNode = fn><#break></#if></#list>
                        <#lt><@formSingleSubField fieldNode/>

                    <#elseif groupNode?node_name == "field-row">
                        <#list groupNode["field-ref"] as rowFieldRefNode>
                            <#assign fieldRef = rowFieldRefNode["@name"]>
                            <#assign fieldNode = "invalid">
                            <#list formNode["field"] as fn><#if fn["@name"] == fieldRef><#assign fieldNode = fn><#break></#if></#list>
                            <#t><@formSingleSubField fieldNode/>
                        </#list>

                    </#if>
                </#list>
            </#if>
        </#list>
    <#else/>
        <#list formNode["field"] as fieldNode>
            <#lt><@formSingleSubField fieldNode/>

        </#list>
    </#if>

</#macro>
<#macro formSingleSubField fieldNode>
    <#list fieldNode["conditional-field"] as fieldSubNode>
        <#if ec.resource.condition(fieldSubNode["@condition"], "")>
            <#t><@formSingleWidget fieldSubNode/>
            <#return>
        </#if>
    </#list>
    <#if fieldNode["default-field"]?has_content>
        <#t><@formSingleWidget fieldNode["default-field"][0]/>
        <#return>
    </#if>
</#macro>
<#macro formSingleWidget fieldSubNode>
    <#if fieldSubNode["ignored"]?has_content || fieldSubNode["hidden"]?has_content || fieldSubNode["submit"]?has_content ||
            fieldSubNode?parent["@hide"]?if_exists == "true"><#return/></#if>
<@fieldTitle fieldSubNode/>: <#recurse fieldSubNode/> </#macro>

<#macro "form-list">
    <#-- Use the formNode assembled based on other settings instead of the straight one from the file: -->
    <#assign formNode = sri.getFtlFormNode(.node["@name"])>
    <#assign listName = formNode["@list"]>
    <#assign listObject = ec.resource.expression(listName, "")?if_exists>
    <#assign formListColumnList = formNode["form-list-column"]?if_exists>
    <#if formListColumnList?exists && (formListColumnList?size > 0)>
        <#list formListColumnList as fieldListColumn>
            <#list fieldListColumn["field-ref"] as fieldRef>
                <#assign fieldRef = fieldRef["@name"]>
                <#assign fieldNode = "invalid">
                <#list formNode["field"] as fn><#if fn["@name"] == fieldRef><#assign fieldNode = fn><#break></#if></#list>
                <#if !(fieldNode["@hide"]?if_exists == "true" ||
                        ((!fieldNode["@hide"]?has_content) && fieldNode?children?size == 1 &&
                        (fieldNode?children[0]["hidden"]?has_content || fieldNode?children[0]["ignored"]?has_content)))>
                    <#t><@formListHeaderField fieldNode/>${"\t"}
                </#if>
            </#list>
        </#list>

        <#list listObject as listEntry>
            <#assign listEntryIndex = listEntry_index>
            <#-- NOTE: the form-list.@list-entry attribute is handled in the ScreenForm class through this call: -->
            <#t>${sri.startFormListRow(formNode["@name"], listEntry, listEntry_index, listEntry_has_next)}
            <#list formNode["form-list-column"] as fieldListColumn>
                <#list fieldListColumn["field-ref"] as fieldRef>
                    <#assign fieldRef = fieldRef["@name"]>
                    <#assign fieldNode = "invalid">
                    <#list formNode["field"] as fn><#if fn["@name"] == fieldRef><#assign fieldNode = fn><#break></#if></#list>
                    <#t><@formListSubField fieldNode/>${"\t"}
                </#list>
            </#list>

            <#t>${sri.endFormListRow()}
        </#list>
        <#t>${sri.safeCloseList(listObject)}<#-- if listObject is an EntityListIterator, close it -->
    <#else>
        <#list formNode["field"] as fieldNode>
            <#if !(fieldNode["@hide"]?if_exists == "true" ||
                    ((!fieldNode["@hide"]?has_content) && fieldNode?children?size == 1 &&
                    (fieldNode?children[0]["hidden"]?has_content || fieldNode?children[0]["ignored"]?has_content)))>
                <#t><@formListHeaderField fieldNode/>${"\t"}
            </#if>
        </#list>

        <#list listObject as listEntry>
            <#assign listEntryIndex = listEntry_index>
            <#-- NOTE: the form-list.@list-entry attribute is handled in the ScreenForm class through this call: -->
            <#t>${sri.startFormListRow(formNode["@name"], listEntry, listEntry_index, listEntry_has_next)}
            <#list formNode["field"] as fieldNode>
                <#t><@formListSubField fieldNode/>${"\t"}
            </#list>

            <#t>${sri.endFormListRow()}
        </#list>
        <#t>${sri.safeCloseList(listObject)}<#-- if listObject is an EntityListIterator, close it -->
    </#if>

</#macro>
<#macro formListHeaderField fieldNode>
    <#if fieldNode["header-field"]?has_content>
        <#assign fieldSubNode = fieldNode["header-field"][0]>
    <#elseif fieldNode["default-field"]?has_content>
        <#assign fieldSubNode = fieldNode["default-field"][0]>
    <#else>
        <#-- this only makes sense for fields with a single conditional -->
        <#assign fieldSubNode = fieldNode["conditional-field"][0]>
    </#if>
    <#t><@fieldTitle fieldSubNode/>
</#macro>
<#macro formListSubField fieldNode>
    <#list fieldNode["conditional-field"] as fieldSubNode>
        <#if ec.resource.condition(fieldSubNode["@condition"], "")>
            <#t><@formListWidget fieldSubNode/>
            <#return>
        </#if>
    </#list>
    <#if fieldNode["default-field"]?has_content>
        <#t><@formListWidget fieldNode["default-field"][0]/>
        <#return>
    </#if>
</#macro>
<#macro formListWidget fieldSubNode>
    <#if fieldSubNode["ignored"]?has_content || fieldSubNode["hidden"]?has_content || fieldSubNode["submit"]?has_content ||
            fieldSubNode?parent["@hide"]?if_exists == "true"><#return/></#if>
    <#t><#recurse fieldSubNode>
</#macro>
<#macro "row-actions"><#-- do nothing, these are run by the SRI --></#macro>

<#macro fieldTitle fieldSubNode><#assign titleValue><#if fieldSubNode["@title"]?has_content>${fieldSubNode["@title"]}<#else/><#list fieldSubNode?parent["@name"]?split("(?=[A-Z])", "r") as nameWord>${nameWord?cap_first?replace("Id", "ID")}<#if nameWord_has_next> </#if></#list></#if></#assign>${ec.l10n.localize(titleValue)}</#macro>

<#macro "field"><#-- shouldn't be called directly, but just in case --><#recurse/></#macro>
<#macro "conditional-field"><#-- shouldn't be called directly, but just in case --><#recurse/></#macro>
<#macro "default-field"><#-- shouldn't be called directly, but just in case --><#recurse/></#macro>

<#-- ================== Form Field Widgets ==================== -->

<#macro "check">
    <#assign options = {"":""}/><#assign options = sri.getFieldOptions(.node)>
    <#assign currentValue = sri.getFieldValue(.node?parent?parent, "")>
    <#if !currentValue?has_content><#assign currentValue = .node["@no-current-selected-key"]?if_exists/></#if>
    <#t><#if currentValue?has_content>${options.get(currentValue)?default(currentValue)}</#if>
</#macro>

<#macro "date-find"></#macro>
<#macro "date-time">
    <#assign fieldValue = sri.getFieldValue(.node?parent?parent, .node["@default-value"]!"")>
    <#if .node["@format"]?has_content><#assign fieldValue = ec.l10n.format(fieldValue, .node["@format"])></#if>
    <#if .node["@type"]?if_exists == "time"><#assign size=9/><#assign maxlength=12/><#elseif .node["@type"]?if_exists == "date"><#assign size=10/><#assign maxlength=10/><#else><#assign size=23/><#assign maxlength=23/></#if>
    <#t>${fieldValue}
</#macro>

<#macro "display">
    <#assign fieldValue = ""/>
    <#if .node["@text"]?has_content>
        <#assign fieldValue = ec.resource.expand(.node["@text"], "")>
        <#if .node["@currency-unit-field"]?has_content>
            <#assign fieldValue = ec.l10n.formatCurrency(fieldValue, ec.resource.expression(.node["@currency-unit-field"], ""), 2)>
        </#if>
    <#elseif .node["@currency-unit-field"]?has_content>
        <#assign fieldValue = ec.l10n.formatCurrency(sri.getFieldValue(.node?parent?parent, ""), ec.resource.expression(.node["@currency-unit-field"], ""), 2)>
    <#else>
        <#assign fieldValue = sri.getFieldValueString(.node?parent?parent, "", .node["@format"]?if_exists)>
    </#if>
    <#t>${fieldValue}
</#macro>
<#macro "display-entity">
    <#assign fieldValue = ""/><#assign fieldValue = sri.getFieldEntityValue(.node)/>
    <#t>${fieldValue}
</#macro>

<#macro "drop-down">
    <#assign options = {"":""}/><#assign options = sri.getFieldOptions(.node)>
    <#assign currentValue = sri.getFieldValueString(.node?parent?parent, "", null)/>
    <#if !currentValue?has_content><#assign currentValue = .node["@no-current-selected-key"]?if_exists/></#if>
    <#t><#if currentValue?has_content>${options.get(currentValue)?default(currentValue)}</#if>
</#macro>

<#macro "file"></#macro>
<#macro "hidden"></#macro>
<#macro "ignored"><#-- shouldn't ever be called as it is checked in the form-* macros --></#macro>
<#macro "password"></#macro>

<#macro "radio">
    <#assign options = {"":""}/><#assign options = sri.getFieldOptions(.node)>
    <#assign currentValue = sri.getFieldValueString(.node?parent?parent, "", null)/>
    <#if !currentValue?has_content><#assign currentValue = .node["@no-current-selected-key"]?if_exists/></#if>
    <#t><#if currentValue?has_content>${options.get(currentValue)?default(currentValue)}</#if>
</#macro>

<#macro "range-find"></#macro>
<#macro "reset"></#macro>

<#macro "submit">
    <#assign fieldValue><@fieldTitle .node?parent/></#assign>
    <#t>${fieldValue}
</#macro>

<#macro "text-area">
    <#assign fieldValue = sri.getFieldValue(.node?parent?parent, .node["@default-value"]!"")>
    <#t>${fieldValue}
</#macro>

<#macro "text-line">
    <#assign fieldValue = sri.getFieldValue(.node?parent?parent, .node["@default-value"]!"")>
    <#t>${fieldValue}
</#macro>

<#macro "text-find">
    <#assign fieldValue = sri.getFieldValue(.node?parent?parent, .node["@default-value"]!"")>
    <#t>${fieldValue}
</#macro>
