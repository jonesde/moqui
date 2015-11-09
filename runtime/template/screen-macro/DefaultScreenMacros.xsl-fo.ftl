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

<#macro attributeValue textValue>${Static["org.moqui.impl.StupidUtilities"].encodeForXmlAttribute(textValue, true)}</#macro>

<#macro @element><fo:block>=== Doing nothing for element ${.node?node_name}, not yet implemented. ===</fo:block></#macro>

<#macro widgets>
<#if sri.doBoundaryComments()><!-- BEGIN screen[@location=${sri.getActiveScreenDef().location}].widgets --></#if>
<#recurse>
<#if sri.doBoundaryComments()><!-- END   screen[@location=${sri.getActiveScreenDef().location}].widgets --></#if>
</#macro>
<#macro "fail-widgets">
<#if sri.doBoundaryComments()><!-- BEGIN screen[@location=${sri.getActiveScreenDef().location}].fail-widgets --></#if>
<#recurse>
<#if sri.doBoundaryComments()><!-- END   screen[@location=${sri.getActiveScreenDef().location}].fail-widgets --></#if>
</#macro>

<#-- ================ Subscreens ================ -->
<#macro "subscreens-menu"></#macro>
<#macro "subscreens-active">${sri.renderSubscreen()}</#macro>
<#macro "subscreens-panel">${sri.renderSubscreen()}</#macro>

<#-- ================ Section ================ -->
<#macro section>
    <#if sri.doBoundaryComments()><!-- BEGIN section[@name=${.node["@name"]}] --></#if>
    ${sri.renderSection(.node["@name"])}
    <#if sri.doBoundaryComments()><!-- END   section[@name=${.node["@name"]}] --></#if>
</#macro>
<#macro "section-iterate">
    <#if sri.doBoundaryComments()><!-- BEGIN section-iterate[@name=${.node["@name"]}] --></#if>
    ${sri.renderSection(.node["@name"])}
    <#if sri.doBoundaryComments()><!-- END   section-iterate[@name=${.node["@name"]}] --></#if>
</#macro>

<#-- ================ Containers ================ -->
<#macro container>
    <fo:block><#recurse></fo:block>
</#macro>

<#macro "container-panel">
    <#-- NOTE: consider putting header and footer in table spanning 3 columns -->
    <#if .node["panel-header"]?has_content>
    <fo:block><#recurse .node["panel-header"][0]>
    </fo:block></#if>
    <fo:table border="solid black">
        <fo:table-body><fo:table-row>
            <#if .node["panel-left"]?has_content>
            <fo:table-cell padding="3pt"><fo:block><#recurse .node["panel-left"][0]>
            </fo:block></fo:table-cell></#if>
            <fo:table-cell padding="3pt"><fo:block><#recurse .node["panel-center"][0]>
            </fo:block></fo:table-cell>
            <#if .node["panel-right"]?has_content>
            <fo:table-cell padding="3pt"><fo:block><#recurse .node["panel-right"][0]>
            </fo:block></fo:table-cell></#if>
        </fo:table-row></fo:table-body>
    </fo:table>
    <#if .node["panel-footer"]?has_content>
    <fo:block><#recurse .node["panel-footer"][0]>
    </fo:block></#if>
</#macro>

<#macro "container-dialog">
    <fo:block>
    <#recurse>
    </fo:block>
</#macro>

<#-- ==================== Includes ==================== -->
<#macro "include-screen">
<#if sri.doBoundaryComments()><!-- BEGIN include-screen[@location=${.node["@location"]}][@share-scope=${.node["@share-scope"]?if_exists}] --></#if>
${sri.renderIncludeScreen(.node["@location"], .node["@share-scope"]?if_exists)}
<#if sri.doBoundaryComments()><!-- END   include-screen[@location=${.node["@location"]}][@share-scope=${.node["@share-scope"]?if_exists}] --></#if>
</#macro>

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
<#if sri.doBoundaryComments() && !(textToUse["@no-boundary-comment"]?if_exists=="true")><!-- BEGIN render-mode.text[@location=${textToUse["@location"]}][@template=${textToUse["@template"]?default("true")}] --></#if>
    <#-- NOTE: this still won't encode templates that are rendered to the writer -->
    <#lt><#if .node["@encode"]!"false" == "true">${sri.renderText(textToUse["@location"], textToUse["@template"]?if_exists)?html}<#else/>${sri.renderText(textToUse["@location"], textToUse["@template"]?if_exists)}</#if>
<#if sri.doBoundaryComments()><!-- END   render-mode.text[@location=${textToUse["@location"]}][@template=${textToUse["@template"]?default("true")}] --></#if>
        </#if>
        <#assign inlineTemplateSource = textToUse?string/>
        <#if inlineTemplateSource?has_content>
<#if sri.doBoundaryComments() && !(textToUse["@no-boundary-comment"]?if_exists=="true")><!-- BEGIN render-mode.text[inline][@template=${textToUse["@template"]?default("true")}] --></#if>
          <#if !textToUse["@template"]?has_content || textToUse["@template"] == "true">
            <#assign inlineTemplate = [inlineTemplateSource, sri.getActiveScreenDef().location + ".render_mode.text"]?interpret>
            <#lt><@inlineTemplate/>
          <#else/>
            <#lt><#if .node["@encode"]!"false" == "true">${inlineTemplateSource?html}<#else/>${inlineTemplateSource}</#if>
          </#if>
<#if sri.doBoundaryComments()><!-- END   render-mode.text[inline][@template=${textToUse["@template"]?default("true")}] --></#if>
        </#if>
    </#if>
</#if>
</#macro>

<#macro text><#-- do nothing, is used only through "render-mode" --></#macro>

<#-- ================== Standalone Fields ==================== -->
<#macro link>
    <#assign urlInfo = sri.makeUrlByType(.node["@url"], .node["@url-type"]!"transition", .node, .node["@expand-transition-url"]!"true")>
    <#assign linkNode = .node>
    <@linkFormLink linkNode linkNode["@id"]?if_exists urlInfo/>
</#macro>
<#macro linkFormLink linkNode linkFormId urlInfo><fo:block>${ec.resource.expand(linkNode["@text"], "")}</fo:block></#macro>

<#macro image>
    <#-- TODO: make real xsl-fo image -->
    <img src="${sri.makeUrlByType(.node["@url"],.node["@url-type"]!"content",null,"true")}" alt="${.node["@alt"]!"image"}"<#if .node["@id"]?has_content> id="${.node["@id"]}"</#if><#if .node["@width"]?has_content> width="${.node["@width"]}"</#if><#if .node["@height"]?has_content> height="${.node["@height"]}"</#if>/>
</#macro>
<#macro label>
    <#-- TODO: handle label type somehow -->
    <#assign labelType = .node["@type"]?default("span")/>
    <#assign labelValue = ec.resource.expand(.node["@text"], "")/>
    <#if (labelValue?length < 255)><#assign labelValue = ec.l10n.localize(labelValue)/></#if>
    <fo:block><#if !.node["@encode"]?has_content || .node["@encode"] == "true">${labelValue?html?replace("\n", "<br>")}<#else/>${labelValue}</#if></fo:block>
</#macro>
<#macro parameter><#-- do nothing, used directly in other elements --></#macro>

<#-- ====================================================== -->
<#-- ======================= Form ========================= -->
<#macro "form-single">
<#if sri.doBoundaryComments()><!-- BEGIN form-single[@name=${.node["@name"]}] --></#if>
    <#-- Use the formNode assembled based on other settings instead of the straight one from the file: -->
    <#assign formNode = sri.getFtlFormNode(.node["@name"])>
    ${sri.setSingleFormMapInContext(formNode)}
        <#if formNode["field-layout"]?has_content>
            <#assign fieldLayout = formNode["field-layout"][0]>
            <fo:block>
                <#assign accordionId = fieldLayout["@id"]?default(formNode["@name"] + "-accordion")>
                <#assign collapsible = (fieldLayout["@collapsible"] == "true")>
                <#assign collapsibleOpened = false>
                <#list formNode["field-layout"][0]?children as layoutNode>
                    <#if layoutNode?node_name == "field-ref">
                      <#if collapsibleOpened>
                        <#assign collapsibleOpened = false>
                        </fo:block>
                        <script>$("#${accordionId}").accordion({ collapsible: true });</script>
                        <#assign accordionId = accordionId + "_A"><#-- set this just in case another accordion is opened -->
                      </#if>
                        <#assign fieldRef = layoutNode["@name"]>
                        <#assign fieldNode = "invalid">
                        <#list formNode["field"] as fn><#if fn["@name"] == fieldRef><#assign fieldNode = fn><#break></#if></#list>
                        <#if fieldNode == "invalid">
                            <fo:block>Error: could not find field with name [${fieldRef}] referred to in a field-ref.@name attribute.</fo:block>
                        <#else>
                            <@formSingleSubField fieldNode/>
                        </#if>
                    <#elseif layoutNode?node_name == "field-row">
                      <#if collapsibleOpened>
                        <#assign collapsibleOpened = false>
                        </fo:block>
                        <script>$("#${accordionId}").accordion({ collapsible: true });</script>
                        <#assign accordionId = accordionId + "_A"><#-- set this just in case another accordion is opened -->
                      </#if>
                        <fo:block>
                        <#list layoutNode["field-ref"] as rowFieldRefNode>
                            <fo:block>
                                <#assign fieldRef = rowFieldRefNode["@name"]>
                                <#assign fieldNode = "invalid">
                                <#list formNode["field"] as fn><#if fn["@name"] == fieldRef><#assign fieldNode = fn><#break></#if></#list>
                                <#if fieldNode == "invalid">
                                    <fo:block>Error: could not find field with name [${fieldRef}] referred to in a field-ref.@name attribute.</fo:block>
                                <#else>
                                    <@formSingleSubField fieldNode/>
                                </#if>
                            </fo:block>
                        </#list>
                        </fo:block>
                    <#elseif layoutNode?node_name == "field-group">
                      <#if collapsible && !collapsibleOpened><#assign collapsibleOpened = true>
                        <fo:block>
                      </#if>
                        <fo:block>${layoutNode["@title"]?default("Section " + layoutNode_index)}</fo:block>
                        <fo:block>
                            <#list layoutNode?children as groupNode>
                                <#if groupNode?node_name == "field-ref">
                                    <#assign fieldRef = groupNode["@name"]>
                                    <#assign fieldNode = "invalid">
                                    <#list formNode["field"] as fn><#if fn["@name"] == fieldRef><#assign fieldNode = fn><#break></#if></#list>
                                    <@formSingleSubField fieldNode/>
                                <#elseif groupNode?node_name == "field-row">
                                    <fo:block>
                                    <#list groupNode["field-ref"] as rowFieldRefNode>
                                        <fo:block>
                                            <#assign fieldRef = rowFieldRefNode["@name"]>
                                            <#assign fieldNode = "invalid">
                                            <#list formNode["field"] as fn><#if fn["@name"] == fieldRef><#assign fieldNode = fn><#break></#if></#list>
                                            <#if fieldNode == "invalid">
                                                <fo:block>Error: could not find field with name [${fieldRef}] referred to in a field-ref.@name attribute.</fo:block>
                                            <#else>
                                                <@formSingleSubField fieldNode/>
                                            </#if>
                                        </fo:block>
                                    </#list>
                                    </fo:block>
                                </#if>
                            </#list>
                        </fo:block>
                    </#if>
                </#list>
                <#if collapsibleOpened>
                    </fo:block>
                </#if>
            </fo:block>
        <#else/>
            <fo:block>
                <#list formNode["field"] as fieldNode><@formSingleSubField fieldNode/></#list>
            </fo:block>
        </#if>
<#if sri.doBoundaryComments()><!-- END   form-single[@name=${.node["@name"]}] --></#if>
</#macro>
<#macro formSingleSubField fieldNode>
    <#list fieldNode["conditional-field"] as fieldSubNode>
        <#if ec.resource.condition(fieldSubNode["@condition"], "")>
            <@formSingleWidget fieldSubNode/>
            <#return>
        </#if>
    </#list>
    <#if fieldNode["default-field"]?has_content>
        <@formSingleWidget fieldNode["default-field"][0]/>
        <#return>
    </#if>
</#macro>
<#macro formSingleWidget fieldSubNode>
    <#if fieldSubNode["ignored"]?has_content && (fieldSubNode?parent["@hide"]?if_exists != "false")><#return></#if>
    <#if fieldSubNode["hidden"]?has_content && (fieldSubNode?parent["@hide"]?if_exists != "false")><#recurse fieldSubNode/><#return></#if>
    <#if fieldSubNode?parent["@hide"]?if_exists == "true"><#return></#if>
    <fo:block>
        <#if !fieldSubNode["submit"]?has_content><label class="form-title" for="${formNode["@name"]}_${fieldSubNode?parent["@name"]}"><@fieldTitle fieldSubNode/></label></#if>
        <#recurse fieldSubNode/>
    </fo:block>
</#macro>

<#macro "form-list">
<#if sri.doBoundaryComments()><!-- BEGIN form-list[@name=${.node["@name"]}] --></#if>
    <#-- Use the formNode assembled based on other settings instead of the straight one from the file: -->
    <#assign formNode = sri.getFtlFormNode(.node["@name"])>
    <#assign isMulti = formNode["@multi"]?if_exists == "true">
    <#assign isMultiFinalRow = false>
    <#assign urlInfo = sri.makeUrlByType(formNode["@transition"], "transition", null, "false")>
    <#assign listName = formNode["@list"]>
    <#assign listObject = ec.resource.expression(listName, "")?if_exists>
    <#assign formListColumnList = formNode["form-list-column"]?if_exists>

    <#if !(formNode["@paginate"]?if_exists == "false") && context[listName + "Count"]?exists && (context[listName + "Count"]?if_exists > 0)>
        <fo:block>${context[listName + "PageRangeLow"]} - ${context[listName + "PageRangeHigh"]} / ${context[listName + "Count"]}</fo:block>
    </#if>
    <#if formListColumnList?exists && (formListColumnList?size > 0)>
        <fo:table>
        <#assign needHeaderForm = sri.isFormHeaderForm(formNode["@name"])>
            <fo:table-header>
                <fo:table-row class="form-header">
                    <#list formListColumnList as fieldListColumn>
                        <fo:table-cell>
                        <#list fieldListColumn["field-ref"] as fieldRef>
                            <#assign fieldRefName = fieldRef["@name"]>
                            <#assign fieldNode = "invalid">
                            <#list formNode["field"] as fn><#if fn["@name"] == fieldRefName><#assign fieldNode = fn><#break></#if></#list>
                            <#if fieldNode == "invalid">
                                <fo:block>Error: could not find field with name [${fieldRefName}] referred to in a form-list-column.field-ref.@name attribute.</fo:block>
                            <#else>
                                <#if !(fieldNode["@hide"]?if_exists == "true" ||
                                        ((!fieldNode["@hide"]?has_content) && fieldNode?children?size == 1 &&
                                        (fieldNode?children[0]["hidden"]?has_content || fieldNode?children[0]["ignored"]?has_content)))>
                                    <fo:block><@formListHeaderField fieldNode/></fo:block>
                                </#if>
                            </#if>
                        </#list>
                        </fo:table-cell>
                    </#list>
                </fo:table-row>
            </fo:table-header>
            <fo:table-body>
                <#list listObject as listEntry>
                    <#assign listEntryIndex = listEntry_index>
                    <#-- NOTE: the form-list.@list-entry attribute is handled in the ScreenForm class through this call: -->
                    ${sri.startFormListRow(formNode["@name"], listEntry, listEntry_index, listEntry_has_next)}
                    <fo:table-row>
                        <#list formNode["form-list-column"] as fieldListColumn>
                            <fo:table-cell>
                            <#list fieldListColumn["field-ref"] as fieldRef>
                                <#assign fieldRefName = fieldRef["@name"]>
                                <#assign fieldNode = "invalid">
                                <#list formNode["field"] as fn><#if fn["@name"] == fieldRefName><#assign fieldNode = fn><#break></#if></#list>
                                <#if fieldNode == "invalid">
                                    <fo:block>Error: could not find field with name [${fieldRefName}] referred to in a form-list-column.field-ref.@name attribute.</fo:block>
                                <#else>
                                    <@formListSubField fieldNode/>
                                </#if>
                            </#list>
                            </fo:table-cell>
                        </#list>
                    </fo:table-row>
                    ${sri.endFormListRow()}
                </#list>
            </fo:table-body>
            ${sri.safeCloseList(listObject)}<#-- if listObject is an EntityListIterator, close it -->
        </fo:table>
    <#else>
        <fo:table>
            <fo:table-header>
                <fo:table-row>
                    <#list formNode["field"] as fieldNode>
                        <#if !(fieldNode["@hide"]?if_exists == "true" ||
                                ((!fieldNode["@hide"]?has_content) && fieldNode?children?size == 1 &&
                                (fieldNode?children[0]["hidden"]?has_content || fieldNode?children[0]["ignored"]?has_content)))>
                            <fo:table-cell><fo:block><@formListHeaderField fieldNode/></fo:block></fo:table-cell>
                        </#if>
                    </#list>
                </fo:table-row>
            </fo:table-header>
            <fo:table-body>
                <#list listObject as listEntry>
                    <#assign listEntryIndex = listEntry_index>
                    <#-- NOTE: the form-list.@list-entry attribute is handled in the ScreenForm class through this call: -->
                    ${sri.startFormListRow(formNode["@name"], listEntry, listEntry_index, listEntry_has_next)}
                    <fo:table-row>
                        <#list formNode["field"] as fieldNode><@formListSubField fieldNode/></#list>
                    </fo:table-row>
                    ${sri.endFormListRow()}
                </#list>
            </fo:table-body>
            ${sri.safeCloseList(listObject)}<#-- if listObject is an EntityListIterator, close it -->
        </fo:table>
    </#if>
<#if sri.doBoundaryComments()><!-- END   form-list[@name=${.node["@name"]}] --></#if>
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
    <fo:block><#-- TODO: make bold/etc -->
        <#if fieldSubNode["submit"]?has_content><#else/><@fieldTitle fieldSubNode/></#if>
    </fo:block>
    <#if fieldNode["header-field"]?has_content && fieldNode["header-field"][0]?children?has_content>
    <fo:block><#-- TODO: make bold/etc -->
        <#recurse fieldNode["header-field"][0]/>
    </fo:block>
    </#if>
</#macro>
<#macro formListSubField fieldNode>
    <#list fieldNode["conditional-field"] as fieldSubNode>
        <#if ec.resource.condition(fieldSubNode["@condition"], "")>
            <@formListWidget fieldSubNode/>
            <#return>
        </#if>
    </#list>
    <#if fieldNode["default-field"]?has_content>
        <@formListWidget fieldNode["default-field"][0]/>
        <#return>
    </#if>
</#macro>
<#macro formListWidget fieldSubNode>
    <#if fieldSubNode["ignored"]?has_content><#return/></#if>
    <#if fieldSubNode["hidden"]?has_content><#recurse fieldSubNode/><#return/></#if>
    <#if fieldSubNode?parent["@hide"]?if_exists == "true"><#return></#if>
    <fo:table-cell wrap-option="wrap"><fo:block>
        <#t><#if isMulti && !isMultiFinalRow && fieldSubNode["submit"]?has_content><#return/></#if>
        <#t><#if isMulti && isMultiFinalRow && !fieldSubNode["submit"]?has_content><#return/></#if>
        <#list fieldSubNode?children as widgetNode>
            <#if widgetNode?node_name == "link">
                <#assign linkNode = widgetNode>
                <#assign urlInfo = sri.makeUrlByType(linkNode["@url"], linkNode["@url-type"]!"transition", linkNode, linkNode["@expand-transition-url"]!"true")>
                <#assign linkFormId><@fieldId linkNode/></#assign>
                <#assign afterFormText><@linkFormForm linkNode linkFormId urlInfo/></#assign>
                <#t>${sri.appendToAfterScreenWriter(afterFormText)}
                <#t><@linkFormLink linkNode linkFormId urlInfo/>
            <#else>
                <#t><#visit widgetNode>
            </#if>
        </#list>
    </fo:block></fo:table-cell>
</#macro>
<#macro "row-actions"><#-- do nothing, these are run by the SRI --></#macro>

<#macro fieldName widgetNode><#assign fieldNode=widgetNode?parent?parent/>${fieldNode["@name"]?html}<#if isMulti?exists && isMulti && listEntryIndex?exists>_${listEntryIndex}</#if></#macro>
<#macro fieldId widgetNode><#assign fieldNode=widgetNode?parent?parent/>${fieldNode?parent["@name"]}_${fieldNode["@name"]}<#if listEntryIndex?exists>_${listEntryIndex}</#if></#macro>
<#macro fieldTitle fieldSubNode><#assign titleValue><#if fieldSubNode["@title"]?has_content>${fieldSubNode["@title"]}<#else/><#list fieldSubNode?parent["@name"]?split("(?=[A-Z])", "r") as nameWord>${nameWord?cap_first?replace("Id", "ID")}<#if nameWord_has_next> </#if></#list></#if></#assign>${ec.l10n.localize(titleValue)}</#macro>

<#macro field><#-- shouldn't be called directly, but just in case --><#recurse/></#macro>
<#macro "conditional-field"><#-- shouldn't be called directly, but just in case --><#recurse/></#macro>
<#macro "default-field"><#-- shouldn't be called directly, but just in case --><#recurse/></#macro>

<#-- ================== Form Field Widgets ==================== -->

<#macro check>
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
    <#t><@attributeValue fieldValue/>
</#macro>

<#macro display>
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
    <#t><@attributeValue fieldValue/>
</#macro>
<#macro "display-entity">
    <#assign fieldValue = ""/><#assign fieldValue = sri.getFieldEntityValue(.node)/>
    <#t><@attributeValue fieldValue/>
</#macro>

<#macro "drop-down">
    <#assign options = {"":""}/><#assign options = sri.getFieldOptions(.node)>
    <#assign currentValue = sri.getFieldValueString(.node?parent?parent, "", null)/>
    <#if !currentValue?has_content><#assign currentValue = .node["@no-current-selected-key"]?if_exists/></#if>
    <#t><#if currentValue?has_content>${options.get(currentValue)?default(currentValue)}</#if>
</#macro>

<#macro file></#macro>
<#macro hidden></#macro>
<#macro ignored><#-- shouldn't ever be called as it is checked in the form-* macros --></#macro>
<#macro password></#macro>

<#macro radio>
    <#assign options = {"":""}/><#assign options = sri.getFieldOptions(.node)>
    <#assign currentValue = sri.getFieldValueString(.node?parent?parent, "", null)/>
    <#if !currentValue?has_content><#assign currentValue = .node["@no-current-selected-key"]?if_exists/></#if>
    <#t><#if currentValue?has_content>${options.get(currentValue)?default(currentValue)}</#if>
</#macro>

<#macro "range-find"></#macro>
<#macro reset></#macro>

<#macro submit>
    <#assign fieldValue><@fieldTitle .node?parent/></#assign>
    <#t><@attributeValue fieldValue/>
</#macro>

<#macro "text-area">
    <#assign fieldValue = sri.getFieldValueString(.node?parent?parent, .node["@default-value"]!"", null)>
    <#t><@attributeValue fieldValue/>
</#macro>

<#macro "text-line">
    <#assign fieldValue = sri.getFieldValueString(.node?parent?parent, .node["@default-value"]!"", null)>
    <#t><@attributeValue fieldValue/>
</#macro>

<#macro "text-find">
    <#assign fieldValue = sri.getFieldValueString(.node?parent?parent, .node["@default-value"]!"", null)>
    <#t><@attributeValue fieldValue/>
</#macro>
