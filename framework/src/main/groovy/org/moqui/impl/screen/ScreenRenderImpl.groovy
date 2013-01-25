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
package org.moqui.impl.screen

import freemarker.template.Template

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import org.apache.commons.codec.net.URLCodec
import org.apache.commons.collections.map.ListOrderedMap

import org.moqui.BaseException
import org.moqui.context.ExecutionContext
import org.moqui.context.ScreenRender
import org.moqui.context.TemplateRenderer
import org.moqui.entity.EntityException
import org.moqui.entity.EntityList
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ContextStack
import org.moqui.impl.screen.ScreenDefinition.ResponseItem
import org.moqui.impl.context.WebFacadeImpl
import org.moqui.impl.StupidWebUtilities
import org.moqui.impl.FtlNodeWrapper
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.screen.ScreenDefinition.SubscreensItem
import org.moqui.impl.entity.EntityDefinition
import org.moqui.entity.EntityCondition.ComparisonOperator
import org.moqui.impl.entity.EntityValueImpl
import org.moqui.impl.StupidUtilities

class ScreenRenderImpl implements ScreenRender {
    protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ScreenRenderImpl.class)
    protected final static URLCodec urlCodec = new URLCodec()

    protected final ScreenFacadeImpl sfi
    protected boolean rendering = false

    protected String rootScreenLocation = null
    protected ScreenDefinition rootScreenDef = null

    protected List<String> originalScreenPathNameList = new ArrayList<String>()
    protected ScreenUrlInfo screenUrlInfo = null
    protected Map<String, ScreenUrlInfo> subscreenUrlInfos = new HashMap()
    protected int screenPathIndex = 0

    protected String baseLinkUrl = null
    protected String servletContextPath = null
    protected String webappName = null

    protected String renderMode = null
    protected String characterEncoding = null
    /** For HttpServletRequest/Response renders this will be set on the response either as this default or a value
     * determined during render, especially for screen sub-content based on the extension of the filename. */
    protected String outputContentType = null

    protected String macroTemplateLocation = null
    protected Boolean boundaryComments = null

    protected HttpServletRequest request = null
    protected HttpServletResponse response = null
    protected Writer internalWriter = null
    protected Writer afterFormWriter = null

    protected boolean dontDoRender = false

    protected Map<String, Node> screenFormNodeCache = new HashMap()

    ScreenRenderImpl(ScreenFacadeImpl sfi) {
        this.sfi = sfi
    }

    Writer getWriter() {
        if (internalWriter != null) return internalWriter
        if (response != null) {
            internalWriter = response.getWriter()
            return internalWriter
        }
        throw new BaseException("Could not render screen, no writer available")
    }

    ExecutionContext getEc() { return sfi.ecfi.getExecutionContext() }
    ScreenFacadeImpl getSfi() { return sfi }
    ScreenUrlInfo getScreenUrlInfo() { return screenUrlInfo }

    @Override
    ScreenRender rootScreen(String rootScreenLocation) { this.rootScreenLocation = rootScreenLocation; return this }

    ScreenRender rootScreenFromHost(String host) {
        for (Node rootScreenNode in getWebappNode()."root-screen") {
            if (host.matches((String) rootScreenNode."@host")) return this.rootScreen(rootScreenNode."@location")
        }
        throw new BaseException("Could not find root screen for host [${host}]")
    }

    @Override
    ScreenRender screenPath(List<String> screenNameList) { this.originalScreenPathNameList.addAll(screenNameList); return this }

    @Override
    ScreenRender renderMode(String renderMode) { this.renderMode = renderMode; return this }

    String getRenderMode() { return this.renderMode }

    @Override
    ScreenRender encoding(String characterEncoding) { this.characterEncoding = characterEncoding;  return this }

    @Override
    ScreenRender macroTemplate(String mtl) { this.macroTemplateLocation = mtl; return this }

    @Override
    ScreenRender baseLinkUrl(String blu) { this.baseLinkUrl = blu; return this }

    @Override
    ScreenRender servletContextPath(String scp) { this.servletContextPath = scp; return this }

    @Override
    ScreenRender webappName(String wan) { this.webappName = wan; return this }

    @Override
    void render(HttpServletRequest request, HttpServletResponse response) {
        if (rendering) throw new IllegalStateException("This screen render has already been used")
        rendering = true
        this.request = request
        this.response = response
        // NOTE: don't get the writer at this point, we don't yet know if we're writing text or binary
        if (!webappName) webappName(request.session.servletContext.getInitParameter("moqui-name"))
        if (webappName && !rootScreenLocation) rootScreenFromHost(request.getServerName())
        if (!originalScreenPathNameList) screenPath(request.getPathInfo().split("/") as List)
        // now render
        internalRender()
    }

    @Override
    void render(Writer writer) {
        if (rendering) throw new IllegalStateException("This screen render has already been used")
        rendering = true
        internalWriter = writer
        internalRender()
    }

    @Override
    String render() {
        if (rendering) throw new IllegalStateException("This screen render has already been used")
        rendering = true
        internalWriter = new StringWriter()
        internalRender()
        return internalWriter.toString()
    }

    /** this should be called as part of a always-actions or pre-actions block to stop rendering before it starts */
    void sendRedirectAndStopRender(String redirectUrl) {
        if (response != null) {
            response.sendRedirect(redirectUrl)
            dontDoRender = true
            logger.info("Redirecting to [${redirectUrl}] instead of rendering [${this.getScreenUrlInfo().getFullPathNameList()}]")
        }
    }

    protected ResponseItem recursiveRunTransition(Iterator<ScreenDefinition> sdIterator) {
        ScreenDefinition sd = sdIterator.next()
        // for these authz is not required, as long as something authorizes on the way to the transition, or
        // the transition itself, it's fine
        ec.artifactExecution.push(
                new ArtifactExecutionInfoImpl(sd.location, "AT_XML_SCREEN", "AUTHZA_VIEW"), false)

        boolean loggedInAnonymous = false
        Node screenNode = sd.getScreenNode()
        if (screenNode."@require-authentication" == "anonymous-all") {
            ec.artifactExecution.setAnonymousAuthorizedAll()
            loggedInAnonymous = ec.getUser().loginAnonymousIfNoUser()
        } else if (screenNode."@require-authentication" == "anonymous-view") {
            ec.artifactExecution.setAnonymousAuthorizedView()
            loggedInAnonymous = ec.getUser().loginAnonymousIfNoUser()
        }

        if (sd.alwaysActions != null) sd.alwaysActions.run(ec)

        ResponseItem ri = null
        if (sdIterator.hasNext()) {
            ri = recursiveRunTransition(sdIterator)
        } else {
            // run the transition
            if (screenUrlInfo.getExtraPathNameList() && screenUrlInfo.targetTransition.getPathParameterList()) {
                List<String> pathParameterList = screenUrlInfo.targetTransition.getPathParameterList()
                int i = 0
                for (String extraPathName in screenUrlInfo.getExtraPathNameList()) {
                    if (pathParameterList.size() > i) {
                        if (ec.web) ec.web.addDeclaredPathParameter(pathParameterList.get(i), extraPathName)
                        ec.context.put(pathParameterList.get(i), extraPathName)
                        i++
                    } else {
                        break
                    }
                }
            }

            ri = screenUrlInfo.targetTransition.run(this)
        }

        ec.artifactExecution.pop()
        if (loggedInAnonymous) ec.getUser().logoutAnonymousOnly()

        return ri
    }

    protected void internalRender() {
        rootScreenDef = sfi.getScreenDefinition(rootScreenLocation)
        if (!rootScreenDef) throw new BaseException("Could not find screen at location [${rootScreenLocation}]")

        if (logger.traceEnabled) logger.trace("Rendering screen [${rootScreenLocation}] with path list [${originalScreenPathNameList}]")

        this.screenUrlInfo = new ScreenUrlInfo(this, rootScreenDef, originalScreenPathNameList, null)
        if (ec.web) {
            // clear out the parameters used for special screen URL config
            if (ec.web.requestParameters.lastStandalone) ec.web.requestParameters.lastStandalone = ""

            // if screenUrlInfo has any parameters add them to the request (probably came from a transition acting as an alias)
            Map<String, String> suiParameterMap = this.screenUrlInfo.parameterMap
            if (suiParameterMap) ec.web.requestParameters.putAll(suiParameterMap)

            // add URL parameters, if there were any in the URL (in path info or after ?)
            this.screenUrlInfo.addParameters(ec.web.requestParameters)
        }

        // check webapp settings for each screen in the path
        for (ScreenDefinition checkSd in screenUrlInfo.screenRenderDefList) {
            if (!checkWebappSettings(checkSd)) return
        }

        // check this here after the ScreenUrlInfo (with transition alias, etc) has already been handled
        if (ec.web && ec.web.requestParameters.renderMode) {
            // we know this is a web request, set defaults if missing
            renderMode = ec.web.requestParameters.renderMode
            String mimeType = sfi.getMimeTypeByMode(renderMode)
            if (mimeType) outputContentType = mimeType
        }

        // if these aren't set in any screen (in the checkWebappSettings method), set them here
        if (!renderMode) renderMode = "html"
        if (!characterEncoding) characterEncoding = "UTF-8"
        if (!outputContentType) outputContentType = "text/html"


        // before we render, set the character encoding (set the content type later, after we see if there is sub-content with a different type)
        if (this.response != null) response.setCharacterEncoding(this.characterEncoding)


        // if there is a transition run that INSTEAD of the screen to render
        if (screenUrlInfo.targetTransition) {
            // if this transition has actions and request was not secure or any parameters were not in the body
            // return an error, helps prevent XSRF attacks
            if (request != null && screenUrlInfo.targetTransition.actions != null) {
                if ((!request.isSecure() && getWebappNode()."@https-enabled" != "false") ||
                        request.getQueryString() ||
                        StupidWebUtilities.getPathInfoParameterMap(request.getPathInfo())) {
                    throw new IllegalArgumentException(
                        """Cannot run screen transition with actions from non-secure request or with URL
                        parameters for security reasons (they are not encrypted and need to be for data
                        protection and source validation). Change the link this came from to be a
                        form with hidden input fields instead.""")
                }
            }

            long transitionStartTime = System.currentTimeMillis()
            // NOTE: always use a transaction for transition run (actions, etc)
            boolean beganTransaction = sfi.ecfi.transactionFacade.begin(null)
            ResponseItem ri = null
            try {
                ri = recursiveRunTransition(screenUrlInfo.screenPathDefList.iterator())
            } catch (Throwable t) {
                sfi.ecfi.transactionFacade.rollback(beganTransaction, "Error running transition in [${screenUrlInfo.url}]", t)
                throw t
            } finally {
                try {
                    if (sfi.ecfi.transactionFacade.isTransactionInPlace()) {
                        if (ec.getMessage().hasError()) {
                            sfi.ecfi.transactionFacade.rollback(beganTransaction, ec.getMessage().getErrorsString(), null)
                        } else {
                            sfi.ecfi.transactionFacade.commit(beganTransaction)
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error ending screen transition transaction", e)
                }

                if (screenUrlInfo.targetScreen.screenNode."@track-artifact-hit" != "false") {
                    sfi.ecfi.countArtifactHit("transition", ri?.type ?: "", screenUrlInfo.url,
                            (ec.web ? ec.web.requestParameters : null), transitionStartTime,
                            System.currentTimeMillis(), null)
                }
            }

            if (ri == null) throw new IllegalArgumentException("No response found for transition [${screenUrlInfo.targetTransition.name}] on screen [${screenUrlInfo.targetScreen.location}]")

            if (ri.saveCurrentScreen && ec.web != null) {
                StringBuilder screenPath = new StringBuilder()
                for (String pn in screenUrlInfo.fullPathNameList) screenPath.append("/").append(pn)
                ((WebFacadeImpl) ec.web).saveScreenLastInfo(screenPath.toString(), null)
            }

            if (ri.type == "none") return

            String url = ri.url ?: ""
            String urlType = ri.urlType ?: "screen-path"

            // handle screen-last, etc
            if (ec.web != null) {
                WebFacadeImpl wfi = (WebFacadeImpl) ec.web
                if (ri.type == "screen-last" || ri.type == "screen-last-noparam") {
                    String savedUrl = wfi.getRemoveScreenLastPath()
                    urlType = "screen-path"
                    // if no saved URL, just go to root/default; avoid getting stuck on Login screen, etc
                    url = savedUrl ?: "/"
                }
                if (ri.type == "screen-last") {
                    wfi.removeScreenLastParameters(true)
                } else if (ri.type == "screen-last-noparam") {
                    wfi.removeScreenLastParameters(false)
                }
            }

            // either send a redirect for the response, if possible, or just render the response now
            if (this.response != null) {
                // save messages in session before redirecting so they can be displayed on the next screen
                if (ec.web != null) {
                    ((WebFacadeImpl) ec.web).saveMessagesToSession()
                    if (ri.saveParameters) ((WebFacadeImpl) ec.web).saveRequestParametersToSession()
                    if (ec.message.hasError()) ((WebFacadeImpl) ec.web).saveErrorParametersToSession()
                }

                if (urlType == "plain") {
                    response.sendRedirect(url)
                } else {
                    // default is screen-path
                    ScreenUrlInfo fullUrl = buildUrl(rootScreenDef, screenUrlInfo.preTransitionPathNameList, url)
                    fullUrl.addParameters(ri.expandParameters(ec))
                    // if this was a screen-last and the screen has declared parameters include them in the URL
                    Map savedParameters = ((WebFacadeImpl) ec.web)?.getSavedParameters()
                    if (ri.type == "screen-last" && savedParameters && fullUrl.getTargetScreen()?.getParameterMap()) {
                        for (String parmName in fullUrl.getTargetScreen().getParameterMap().keySet()) {
                            if (savedParameters.get(parmName))
                                fullUrl.addParameter(parmName, savedParameters.get(parmName))
                        }
                    }
                    String fullUrlString = fullUrl.getUrlWithParams()
                    logger.info("Finished transition [${getScreenUrlInfo().getFullPathNameList()}], redirecting to [${fullUrlString}]")
                    response.sendRedirect(fullUrlString)
                }
            } else {
                List<String> pathElements = url.split("/") as List
                if (url.startsWith("/")) {
                    this.originalScreenPathNameList = pathElements
                } else {
                    this.originalScreenPathNameList = screenUrlInfo.preTransitionPathNameList
                    this.originalScreenPathNameList.addAll(pathElements)
                }
                // reset screenUrlInfo and call this again to start over with the new target
                screenUrlInfo = null
                internalRender()
            }
        } else if (screenUrlInfo.fileResourceRef != null) {
            long resourceStartTime = System.currentTimeMillis()

            TemplateRenderer tr = sfi.ecfi.resourceFacade.getTemplateRendererByLocation(screenUrlInfo.fileResourceRef.location)

            // use the fileName to determine the content/mime type
            String fileName = screenUrlInfo.fileResourceRef.fileName
            // strip template extension(s) to avoid problems with trying to find content types based on them
            String fileContentType = sfi.ecfi.resourceFacade.getContentType(tr != null ? tr.stripTemplateExtension(fileName) : fileName)

            boolean isBinary = sfi.ecfi.resourceFacade.isBinaryContentType(fileContentType)
            // if (logger.traceEnabled) logger.trace("Content type for screen sub-content filename [${fileName}] is [${fileContentType}], default [${this.outputContentType}], is binary? ${isBinary}")

            if (isBinary) {
                if (response) {
                    this.outputContentType = fileContentType
                    response.setContentType(this.outputContentType)
                    // static binary, tell the browser to cache it
                    // NOTE: make this configurable?
                    response.addHeader("Cache-Control", "max-age=3600, must-revalidate, public")

                    InputStream is
                    try {
                        is = screenUrlInfo.fileResourceRef.openStream()
                        OutputStream os = response.outputStream
                        int totalLen = StupidUtilities.copyStream(is, os)

                        if (screenUrlInfo.targetScreen.screenNode."@track-artifact-hit" != "false") {
                            sfi.ecfi.countArtifactHit("screen-content", fileContentType, screenUrlInfo.url,
                                    (ec.web ? ec.web.requestParameters : null), resourceStartTime,
                                    System.currentTimeMillis(), totalLen)
                        }
                        if (logger.traceEnabled) logger.trace("Sent binary response of length [${totalLen}] with from file [${screenUrlInfo.fileResourceRef.location}] for request to [${screenUrlInfo.url}]")
                        return
                    } finally {
                        if (is != null) is.close()
                    }
                } else {
                    throw new IllegalArgumentException("Tried to get binary content at [${screenUrlInfo.fileResourcePathList}] under screen [${screenUrlInfo.targetScreen.location}], but there is no HTTP response available")
                }
            }

            // not binary, render as text
            if (screenUrlInfo.targetScreen.screenNode."@include-child-content" != "true") {
                // not a binary object (hopefully), read it and write it to the writer
                if (fileContentType) this.outputContentType = fileContentType
                if (response != null) {
                    response.setContentType(this.outputContentType)
                    response.setCharacterEncoding(this.characterEncoding)
                }

                if (tr != null) {
                    // if requires a render, don't cache and make it private
                    if (response != null) response.addHeader("Cache-Control", "no-cache, must-revalidate, private")
                    tr.render(screenUrlInfo.fileResourceRef.location, writer)
                } else {
                    // static text, tell the browser to cache it
                    // TODO: make this configurable?
                    if (response != null) response.addHeader("Cache-Control", "max-age=3600, must-revalidate, public")
                    // no renderer found, just grab the text (cached) and throw it to the writer
                    String text = sfi.ecfi.resourceFacade.getLocationText(screenUrlInfo.fileResourceRef.location, true)
                    if (text) {
                        // NOTE: String.length not correct for byte length
                        String charset = response?.getCharacterEncoding() ?: "UTF-8"
                        int length = text.getBytes(charset).length
                        if (response != null) response.setContentLength(length)

                        if (logger.traceEnabled) logger.trace("Sending text response of length [${length}] with [${charset}] encoding from file [${screenUrlInfo.fileResourceRef.location}] for request to [${screenUrlInfo.url}]")

                        writer.write(text)

                        if (screenUrlInfo.targetScreen.screenNode."@track-artifact-hit" != "false") {
                            sfi.ecfi.countArtifactHit("screen-content", fileContentType, screenUrlInfo.url,
                                    (ec.web ? ec.web.requestParameters : null), resourceStartTime,
                                    System.currentTimeMillis(), length)
                        }
                    } else {
                        logger.warn("Not sending text response from file [${screenUrlInfo.fileResourceRef.location}] for request to [${screenUrlInfo.url}] because no text was found in the file.")
                    }
                }
            } else {
                // render the root screen as normal, and when that is to the targetScreen include the content
                doActualRender()
            }
        } else {
            doActualRender()
        }
    }

    protected void recursiveRunActions(Iterator<ScreenDefinition> screenDefIterator, boolean runAlwaysActions, boolean runPreActions) {
        ScreenDefinition sd = screenDefIterator.next()
        // check authz first, including anonymous-* handling so that permissions and auth are in place
        // NOTE: don't require authz if the screen doesn't require auth
        Node screenNode = sd.getScreenNode()
        ec.artifactExecution.push(new ArtifactExecutionInfoImpl(sd.location, "AT_XML_SCREEN", "AUTHZA_VIEW"),
                !screenDefIterator.hasNext() ? (!screenNode."@require-authentication" || screenNode."@require-authentication" == "true") : false)

        boolean loggedInAnonymous = false
        if (screenNode."@require-authentication" == "anonymous-all") {
            ec.artifactExecution.setAnonymousAuthorizedAll()
            loggedInAnonymous = ec.getUser().loginAnonymousIfNoUser()
        } else if (screenNode."@require-authentication" == "anonymous-view") {
            ec.artifactExecution.setAnonymousAuthorizedView()
            loggedInAnonymous = ec.getUser().loginAnonymousIfNoUser()
        }

        if (runAlwaysActions && sd.alwaysActions != null) sd.alwaysActions.run(ec)
        if (runPreActions && sd.preActions != null) sd.preActions.run(ec)

        if (screenDefIterator.hasNext()) recursiveRunActions(screenDefIterator, runAlwaysActions, runPreActions)

        // all done so pop the artifact info; don't bother making sure this is done on errors/etc like in a finally clause because if there is an error this will help us know how we got there
        ec.artifactExecution.pop()
        if (loggedInAnonymous) ec.getUser().logoutAnonymousOnly()
    }

    void doActualRender() {
        long screenStartTime = System.currentTimeMillis()
        boolean beganTransaction = screenUrlInfo.beginTransaction ? sfi.ecfi.transactionFacade.begin(null) : false
        try {
            // make sure this (sri) is in the context before running actions
            ec.context.put("sri", this)

            // run always-actions for all screens in path
            boolean hasAlwaysActions = false
            for (ScreenDefinition sd in screenUrlInfo.screenPathDefList) if (sd.alwaysActions != null) { hasAlwaysActions = true; break }
            if (hasAlwaysActions) {
                Iterator<ScreenDefinition> screenDefIterator = screenUrlInfo.screenPathDefList.iterator()
                recursiveRunActions(screenDefIterator, true, false)
            }
            // run pre-actions for just the screens that will be rendered
            boolean hasPreActions = false
            for (ScreenDefinition sd in screenUrlInfo.screenRenderDefList) if (sd.preActions != null) { hasPreActions = true; break }
            if (hasPreActions) {
                Iterator<ScreenDefinition> screenDefIterator = screenUrlInfo.screenRenderDefList.iterator()
                recursiveRunActions(screenDefIterator, false, true)
            }

            // if dontDoRender then quit now; this should be set during always-actions or pre-actions
            if (dontDoRender) return

            if (response != null) {
                response.setContentType(this.outputContentType)
                response.setCharacterEncoding(this.characterEncoding)
                // if requires a render, don't cache and make it private
                response.addHeader("Cache-Control", "no-cache, must-revalidate, private")
            }

            // for inherited permissions to work, walk the screen list before the screenRenderDefList and artifact push
            // them, then pop after
            int screensPushed = 0
            if (screenUrlInfo.renderPathDifference > 0) {
                for (int i = 0; i < screenUrlInfo.renderPathDifference; i++) {
                    ScreenDefinition permSd = screenUrlInfo.screenPathDefList.get(i)
                    ec.artifactExecution.push(new ArtifactExecutionInfoImpl(permSd.location, "AT_XML_SCREEN", "AUTHZA_VIEW"), false)
                    screensPushed++
                }
            }

            // start rendering at the root section of the root screen
            ScreenDefinition renderStartDef = screenUrlInfo.screenRenderDefList[0]
            // if screenRenderDefList.size == 1 then it is the target screen, otherwise it's not
            renderStartDef.render(this, screenUrlInfo.screenRenderDefList.size() == 1)

            for (int i = screensPushed; i > 0; i--) ec.artifactExecution.pop()
        } catch (Throwable t) {
            String errMsg = "Error rendering screen [${getActiveScreenDef().location}]"
            sfi.ecfi.transactionFacade.rollback(beganTransaction, errMsg, t)
            throw new RuntimeException(errMsg, t)
        } finally {
            if (beganTransaction && sfi.ecfi.transactionFacade.isTransactionInPlace()) sfi.ecfi.transactionFacade.commit()
            if (screenUrlInfo.targetScreen.screenNode."@track-artifact-hit" != "false") {
                sfi.ecfi.countArtifactHit("screen", this.outputContentType, screenUrlInfo.url,
                        (ec.web ? ec.web.requestParameters : null), screenStartTime, System.currentTimeMillis(), null)
            }
        }
    }

    boolean checkWebappSettings(ScreenDefinition currentSd) {
        if (!request) return true

        if (currentSd.webSettingsNode?."@allow-web-request" == "false")
            throw new IllegalArgumentException("The screen [${currentSd.location}] cannot be used in a web request (allow-web-request=false).")

        if (currentSd.webSettingsNode?."@mime-type") this.outputContentType = currentSd.webSettingsNode?."@mime-type"
        if (currentSd.webSettingsNode?."@character-encoding") this.characterEncoding = currentSd.webSettingsNode?."@character-encoding"

        // if screen requires auth and there is not active user redirect to login screen, save this request
        if (logger.traceEnabled) logger.trace("Checking screen [${currentSd.location}] for require-authentication, current user is [${ec.user.userId}]")
        if ((!(currentSd.screenNode?."@require-authentication") || currentSd.screenNode?."@require-authentication" == "true")
                && !ec.getUser().getUserId() && !ec.getUser().getLoggedInAnonymous()) {
            logger.info("Screen at location [${currentSd.location}], which is part of [${screenUrlInfo.fullPathNameList}] under screen [${screenUrlInfo.fromSd.location}] requires authentication but no user is currently logged in.")
            // save the request as a save-last to use after login
            if (ec.web && screenUrlInfo.fileResourceRef == null) {
                StringBuilder screenPath = new StringBuilder()
                for (String pn in screenUrlInfo.fullPathNameList) screenPath.append("/").append(pn)
                ((WebFacadeImpl) ec.web).saveScreenLastInfo(screenPath.toString(), null)
                // save messages in session before redirecting so they can be displayed on the next screen
                ((WebFacadeImpl) ec.web).saveMessagesToSession()
            }

            // find the last login path from screens in path (whether rendered or not)
            String loginPath = "/Login"
            for (ScreenDefinition sd in screenUrlInfo.screenPathDefList) {
                if (sd.screenNode."@login-path") loginPath = sd.screenNode."@login-path"
            }

            // respond with 401 and the login screen instead of a redirect; JS client libraries handle this much better
            List<String> pathElements = loginPath.split("/") as List
            if (loginPath.startsWith("/")) {
                this.originalScreenPathNameList = pathElements
            } else {
                this.originalScreenPathNameList = screenUrlInfo.preTransitionPathNameList
                this.originalScreenPathNameList.addAll(pathElements)
            }
            // reset screenUrlInfo and call this again to start over with the new target
            screenUrlInfo = null
            internalRender()
            if (response != null) response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)
            return false

            /*
            // now prepare and send the redirect
            ScreenUrlInfo sui = new ScreenUrlInfo(this, rootScreenDef, [], loginPath)
            response.sendRedirect(sui.url)
            return false
            */
        }

        // if request not secure and screens requires secure redirect to https
        if (currentSd.webSettingsNode?."@require-encryption" != "false" && getWebappNode()."@https-enabled" != "false" &&
                !request.isSecure()) {
            logger.info("Screen at location [${currentSd.location}], which is part of [${screenUrlInfo.fullPathNameList}] under screen [${screenUrlInfo.fromSd.location}] requires an encrypted/secure connection but the request is not secure, sending redirect to secure.")
            // save messages in session before redirecting so they can be displayed on the next screen
            if (ec.web) ((WebFacadeImpl) ec.web).saveMessagesToSession()
            // redirect to the same URL this came to
            response.sendRedirect(screenUrlInfo.getUrlWithParams())
            return false
        }

        return true
    }

    Node getWebappNode() {
        if (!webappName) return null
        return (Node) sfi.ecfi.confXmlRoot["webapp-list"][0]["webapp"].find({ it.@name == webappName })
    }

    boolean doBoundaryComments() {
        if (boundaryComments != null) return boundaryComments
        boundaryComments = sfi.ecfi.confXmlRoot."screen-facade"[0]."@boundary-comments" == "true"
        return boundaryComments
    }

    ScreenDefinition getRootScreenDef() { return rootScreenDef }
    ScreenDefinition getActiveScreenDef() { return screenUrlInfo.screenRenderDefList[screenPathIndex] }

    List<String> getActiveScreenPath() {
        // handle case where root screen is first/zero in list versus a standalone screen
        int fullPathIndex = screenUrlInfo.renderPathDifference + screenPathIndex
        return screenUrlInfo.fullPathNameList[0..fullPathIndex-1]
    }

    String renderSubscreen() {
        // first see if there is another screen def in the list
        if ((screenPathIndex+1) >= screenUrlInfo.screenRenderDefList.size()) {
            if (screenUrlInfo.fileResourceRef) {
                // NOTE: don't set this.outputContentType, when including in a screen the screen determines the type
                sfi.ecfi.resourceFacade.renderTemplateInCurrentContext(screenUrlInfo.fileResourceRef.location, writer)
                return ""
            } else {
                return "Tried to render subscreen in screen [${getActiveScreenDef()?.location}] but there is no subscreens.@default-item, and no more valid subscreen names in the screen path [${screenUrlInfo.fullPathNameList}]"
            }
        }

        screenPathIndex++
        ScreenDefinition screenDef = screenUrlInfo.screenRenderDefList[screenPathIndex]
        try {
            writer.flush()
            screenDef.render(this, (screenUrlInfo.screenRenderDefList.size() - 1) == screenPathIndex)
            writer.flush()
        } catch (Throwable t) {
            logger.error("Error rendering screen [${screenDef.location}]", t)
            return "Error rendering screen [${screenDef.location}]: ${t.toString()}"
        } finally {
            screenPathIndex--
        }
        // NOTE: this returns a String so that it can be used in an FTL interpolation, but it always writes to the writer
        return ""
    }

    Template getTemplate() {
        if (macroTemplateLocation) {
            return sfi.getTemplateByLocation(macroTemplateLocation)
        } else {
            String overrideTemplateLocation = null
            // go through the screenPathDefList instead screenRenderDefList so that parent screen can override template
            //     even if it isn't rendered to decorate subscreen
            for (ScreenDefinition sd in screenUrlInfo.screenPathDefList) {
                // go through entire list and set all found, basically we want the last one if there are more than one
                Node mt = (Node) sd.screenNode."macro-template".find({ it."@type" == renderMode })
                if (mt != null) overrideTemplateLocation = mt."@location"
            }
            if (overrideTemplateLocation) {
                return sfi.getTemplateByLocation(overrideTemplateLocation)
            } else {
                return sfi.getTemplateByMode(renderMode)
            }
        }
    }

    String renderSection(String sectionName) {
        ScreenDefinition sd = getActiveScreenDef()
        ScreenSection section = sd.getSection(sectionName)
        if (!section) throw new IllegalArgumentException("No section with name [${sectionName}] in screen [${sd.location}]")
        writer.flush()
        section.render(this)
        writer.flush()
        // NOTE: this returns a String so that it can be used in an FTL interpolation, but it always writes to the writer
        return ""
    }

    String startFormListRow(String formName, Object listEntry) {
        ScreenDefinition sd = getActiveScreenDef()
        ScreenForm form = sd.getForm(formName)
        if (form == null) throw new IllegalArgumentException("No form with name [${formName}] in screen [${sd.location}]")
        ((ContextStack) ec.context).push()
        form.runFormListRowActions(this, listEntry)
        // NOTE: this returns a String so that it can be used in an FTL interpolation, but nothing it written
        return ""
    }
    String endFormListRow() {
        ((ContextStack) ec.context).pop()
        // NOTE: this returns a String so that it can be used in an FTL interpolation, but nothing it written
        return ""
    }
    String safeCloseList(Object listObject) {
        if (listObject instanceof EntityListIterator) ((EntityListIterator) listObject).close()
        // NOTE: this returns a String so that it can be used in an FTL interpolation, but nothing it written
        return ""
    }
    Node getFormNode(String formName) {
        ScreenDefinition sd = getActiveScreenDef()
        String nodeCacheKey = sd.getLocation() + "#" + formName
        // NOTE: this is cached in the context of the renderer for multiple accesses; because of form overrides may not
        // be valid outside the scope of a single screen render
        Node formNode = screenFormNodeCache.get(nodeCacheKey)
        if (formNode == null) {
            ScreenForm form = sd.getForm(formName)
            if (!form) throw new IllegalArgumentException("No form with name [${formName}] in screen [${sd.location}]")
            formNode = form.getFormNode()
            screenFormNodeCache.put(nodeCacheKey, formNode)
        }
        return formNode
    }
    FtlNodeWrapper getFtlFormNode(String formName) { return FtlNodeWrapper.wrapNode(getFormNode(formName)) }
    List<FtlNodeWrapper> getFtlFormFieldLayoutNonReferencedFieldList(String formName) {
        ScreenDefinition sd = getActiveScreenDef()
        List<Node> fieldNodeList = sd.getForm(formName).getFieldLayoutNonReferencedFieldList()
        List<FtlNodeWrapper> fieldFtlNodeList = []
        for (Node fieldNode in fieldNodeList) fieldFtlNodeList.add(FtlNodeWrapper.wrapNode(fieldNode))
        return fieldFtlNodeList
    }

    boolean isFormUpload(String formName) {
        Node cachedFormNode = this.getFormNode(formName)
        return getActiveScreenDef().getForm(formName).isUpload(cachedFormNode)
    }
    boolean isFormHeaderForm(String formName) {
        Node cachedFormNode = this.getFormNode(formName)
        return getActiveScreenDef().getForm(formName).isFormHeaderForm(cachedFormNode)
    }

    String getFormFieldValidationClasses(String formName, String fieldName) {
        ScreenForm form = getActiveScreenDef().getForm(formName)
        Node cachedFormNode = getFormNode(formName)
        Node parameterNode = form.getFieldInParameterNode(fieldName, cachedFormNode)
        if (parameterNode == null) return ""

        Set<String> vcs = new HashSet()
        if (parameterNode."@required" == "true") vcs.add("required")
        if (parameterNode."number-integer") vcs.add("number")
        if (parameterNode."number-decimal") vcs.add("number")
        if (parameterNode."text-email") vcs.add("email")
        if (parameterNode."text-url") vcs.add("url")
        if (parameterNode."text-digits") vcs.add("digits")
        if (parameterNode."credit-card") vcs.add("creditcard")

        String type = parameterNode."@type"
        if (type && (type.endsWith("BigDecimal") || type.endsWith("BigInteger") || type.endsWith("Long") ||
                type.endsWith("Integer") || type.endsWith("Double") || type.endsWith("Float") ||
                type.endsWith("Number"))) vcs.add("number")

        StringBuilder sb = new StringBuilder()
        for (String vc in vcs) { if (sb) sb.append(" "); sb.append(vc); }
        return sb.toString()
    }

    String renderIncludeScreen(String location, String shareScopeStr) {
        boolean shareScope = shareScopeStr == "true"

        ContextStack cs = (ContextStack) ec.context
        try {
            if (!shareScope) cs.push()
            writer.flush()
            sfi.makeRender().rootScreen(location).renderMode(renderMode).encoding(characterEncoding)
                    .macroTemplate(macroTemplateLocation).render(writer)
            writer.flush()
        } finally {
            if (!shareScope) cs.pop()
        }

        // NOTE: this returns a String so that it can be used in an FTL interpolation, but it always writes to the writer
        return ""
    }

    String renderText(String location, String isTemplateStr) {
        boolean isTemplate = (isTemplateStr != "false")

        if (!location || location == "null") {
            logger.warn("Not rendering text in screen [${getActiveScreenDef().location}], location was empty")
            return ""
        }
        if (isTemplate) {
            writer.flush()
            // NOTE: run templates with their own variable space so we can add sri, and avoid getting anything added from within
            ContextStack cs = (ContextStack) ec.context
            cs.push()
            cs.put("sri", this)
            sfi.ecfi.resourceFacade.renderTemplateInCurrentContext(location, writer)
            cs.pop()
            writer.flush()
            // NOTE: this returns a String so that it can be used in an FTL interpolation, but it always writes to the writer
            return ""
        } else {
            return sfi.ecfi.resourceFacade.getLocationText(location, true) ?: ""
        }
    }

    String appendToAfterFormWriter(String text) {
        if (afterFormWriter == null) afterFormWriter = new StringWriter()
        afterFormWriter.append(text)
        // NOTE: this returns a String so that it can be used in an FTL interpolation, but it always writes to the writer
        return ""
    }
    String getAfterFormWriterText() { return afterFormWriter == null ? "" : afterFormWriter.toString() }

    ScreenUrlInfo buildUrl(String subscreenPath) {
        if (subscreenUrlInfos.containsKey(subscreenPath)) return subscreenUrlInfos.get(subscreenPath)
        ScreenUrlInfo sui = new ScreenUrlInfo(this, null, null, subscreenPath)
        subscreenUrlInfos.put(subscreenPath, sui)
        return sui
    }

    ScreenUrlInfo buildUrl(ScreenDefinition fromSd, List<String> fromPathList, String subscreenPath) {
        ScreenUrlInfo ui = new ScreenUrlInfo(this, fromSd, fromPathList, subscreenPath)
        return ui
    }

    ScreenUrlInfo makeUrlByType(String origUrl, String urlType, FtlNodeWrapper parameterParentNodeWrapper) {
        /* TODO handle urlType=content
            A content location (without the content://). URL will be one that can access that content.
         */
        String url = ec.resource.evaluateStringExpand(origUrl, "")
        ScreenUrlInfo sui
        switch (urlType) {
            // for transition we want a URL relative to the current screen, so just pass that to buildUrl
            case "transition": sui = new ScreenUrlInfo(this, null, null, url); break;
            case "content": throw new IllegalArgumentException("The url-type of content is not yet supported"); break;
            case "plain":
            default: sui = new ScreenUrlInfo(this, url); break;
        }

        if (sui != null && parameterParentNodeWrapper != null) {
            Node parameterParentNode = parameterParentNodeWrapper.groovyNode
            if (parameterParentNode."@parameter-map") {
                def ctxParameterMap = ec.resource.evaluateContextField((String) parameterParentNode."@parameter-map", "")
                if (ctxParameterMap) sui.addParameters((Map) ctxParameterMap)
            }
            for (Node parameterNode in parameterParentNode."parameter")
                sui.addParameter(parameterNode."@name", makeValue(parameterNode."@from" ?: parameterNode."@name", parameterNode."@value"))
        }

        return sui
    }

    String makeValue(String from, String value) {
        if (value) {
            return ec.resource.evaluateStringExpand(value, getActiveScreenDef().location)
        } else if (from) {
            return ec.resource.evaluateContextField(from, getActiveScreenDef().location) as String
        } else {
            return ""
        }
    }

    String getFieldValueString(FtlNodeWrapper fieldNodeWrapper, String defaultValue, String format) {
        Object obj = getFieldValue(fieldNodeWrapper, defaultValue)
        String strValue
        if (format) {
            strValue = ec.l10n.formatValue(obj, format)
        } else {
            strValue = obj ? obj.toString() : ""
        }
        return strValue
    }
    Object getFieldValue(FtlNodeWrapper fieldNodeWrapper, String defaultValue) {
        Node fieldNode = fieldNodeWrapper.getGroovyNode()
        if (fieldNode."@entry-name") return ec.resource.evaluateContextField((String) fieldNode."@entry-name", null)
        String fieldName = fieldNode."@name"
        String mapName = fieldNode.parent()."@map" ?: "fieldValues"
        Object value = null
        // if this is an error situation try parameters first, otherwise try parameters last
        if (ec.web != null && ec.web.errorParameters != null && (ec.web.errorParameters.moquiFormName == fieldNode.parent()."@name"))
            value = ec.web.errorParameters.get(fieldName)
        if (!value && ec.context.get(mapName) && fieldNode.parent().name() == "form-single") {
            try {
                Map valueMap = (Map) ec.context.get(mapName)
                if (valueMap instanceof EntityValueImpl) {
                    // if it is an EntityValueImpl, only get if the fieldName is a value
                    EntityValueImpl evi = (EntityValueImpl) valueMap
                    if (evi.getEntityDefinition().isField(fieldName)) value = evi.get(fieldName)
                } else {
                    value = valueMap.get(fieldName)
                }
            } catch (EntityException e) { /* do nothing, not necessarily an entity field */ }
        }
        if (!value) value = ec.context.get(fieldName)
        // this isn't needed since the parameters are copied to the context: if (!isError && isWebAndSameForm && !value) value = ec.web.parameters.get(fieldName)
        if (value) return value
        return ec.resource.evaluateStringExpand(defaultValue, null)
    }

    String getFieldEntityValue(FtlNodeWrapper widgetNodeWrapper) {
        FtlNodeWrapper fieldNodeWrapper = (FtlNodeWrapper) widgetNodeWrapper.parentNode.parentNode
        Object fieldValue = getFieldValue(fieldNodeWrapper, "")
        if (!fieldValue) return ""
        Node widgetNode = widgetNodeWrapper.getGroovyNode()
        EntityDefinition ed = sfi.ecfi.entityFacade.getEntityDefinition((String) widgetNode."@entity-name")

        // find the entity value
        String keyFieldName = widgetNode."@key-field-name"
        if (!keyFieldName) keyFieldName = ed.getPkFieldNames().get(0)
        EntityValue ev = ec.entity.makeFind((String) widgetNode."@entity-name").condition(keyFieldName, fieldValue)
                .useCache(widgetNode."@use-cache"?:"true" == "true").one()
        if (ev == null) return ""

        String value = ""
        if (widgetNode."@text") {
            // push onto the context and then expand the text
            ec.context.push(ev)
            value = ec.resource.evaluateStringExpand((String) widgetNode."@text", null)
            ec.context.pop()
        } else {
            // get the value of the default description field for the entity
            String defaultDescriptionField = ed.getDefaultDescriptionField()
            if (defaultDescriptionField) value = ev.get(defaultDescriptionField)
        }
        return value
    }

    ListOrderedMap getFieldOptions(FtlNodeWrapper widgetNodeWrapper) {
        return ScreenForm.getFieldOptions(widgetNodeWrapper.getGroovyNode(), ec)
    }

    boolean isInCurrentScreenPath(List<String> pathNameList) {
        if (pathNameList.size() > screenUrlInfo.fullPathNameList.size()) return false
        for (int i = 0; i < pathNameList.size(); i++) {
            if (pathNameList.get(i) != screenUrlInfo.fullPathNameList.get(i)) return false
        }
        return true
    }
    boolean isActiveInCurrentMenu() {
        for (SubscreensItem ssi in getActiveScreenDef().subscreensByName.values()) {
            if (!ssi.menuInclude) continue
            ScreenUrlInfo urlInfo = buildUrl(ssi.name)
            if (urlInfo.inCurrentScreenPath) return true
        }
        return false
    }

    ScreenUrlInfo getCurrentScreenUrl() { return screenUrlInfo }
    URI getBaseLinkUri() {
        String urlString = baseLinkUrl ?: getCurrentScreenUrl().getScreenPathUrl()
        // logger.warn("=================== urlString=${urlString}, baseLinkUrl=${baseLinkUrl}")
        URL blu = new URL(urlString)
        // NOTE: not including user info, query, or fragment... should consider them?
        // NOTE: using the multi-argument constructor so it will encode stuff
        URI baseUri = new URI(blu.getProtocol(), null, blu.getHost(), blu.getPort(), blu.getPath(), null, null)
        return baseUri
    }

    String getCurrentThemeId() {
        String stteId = null
        // loop through entire screenRenderDefList and look for @screen-theme-type-enum-id, use last one found
        if (screenUrlInfo.screenRenderDefList) for (ScreenDefinition sd in screenUrlInfo.screenRenderDefList) {
            if (sd.screenNode?."@screen-theme-type-enum-id") stteId = sd.screenNode?."@screen-theme-type-enum-id"
        }
        // if no setting default to STT_INTERNAL
        if (!stteId) stteId = "STT_INTERNAL"

        // see if there is a user setting for the theme
        String themeId = sfi.ecfi.entityFacade.makeFind("moqui.security.UserScreenTheme")
                .condition([userId:ec.user.userId, screenThemeTypeEnumId:stteId])
                .one()?.screenThemeId
        // use the Enumeration.enumCode from the type to find the theme type's default screenThemeId
        if (!themeId) {
            boolean alreadyDisabled = ec.getArtifactExecution().disableAuthz()
            try {
                EntityValue themeTypeEnum = sfi.ecfi.entityFacade.makeFind("moqui.basic.Enumeration")
                        .condition("enumId", stteId).useCache(true).one()
                if (themeTypeEnum?.enumCode) themeId = themeTypeEnum.enumCode
            } finally {
                if (!alreadyDisabled) ec.getArtifactExecution().enableAuthz()
            }
        }
        // theme with "DEFAULT" in the ID
        if (!themeId) {
            EntityValue stv = sfi.ecfi.entityFacade.makeFind("moqui.screen.ScreenTheme")
                    .condition("screenThemeTypeEnumId", stteId)
                    .condition("screenThemeId", ComparisonOperator.LIKE, "%DEFAULT%").one()
            if (stv) themeId = stv.screenThemeId
        }
        return themeId
    }

    List<String> getThemeValues(String resourceTypeEnumId) {
        EntityList strList = sfi.ecfi.entityFacade.makeFind("moqui.screen.ScreenThemeResource")
                .condition([screenThemeId:getCurrentThemeId(), resourceTypeEnumId:resourceTypeEnumId])
                .orderBy("sequenceNum").list()
        List<String> values = new LinkedList()
        for (EntityValue str in strList) values.add(str.resourceValue as String)
        return values
    }
}
