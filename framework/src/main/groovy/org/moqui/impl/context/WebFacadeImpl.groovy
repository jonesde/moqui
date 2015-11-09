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
package org.moqui.impl.context

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic

import org.apache.commons.codec.binary.Base64
import org.apache.commons.fileupload.FileItem
import org.apache.commons.fileupload.FileItemFactory
import org.apache.commons.fileupload.disk.DiskFileItemFactory
import org.apache.commons.fileupload.servlet.ServletFileUpload

import org.moqui.context.*
import org.moqui.entity.EntityNotFoundException
import org.moqui.entity.EntityValueNotFoundException
import org.moqui.impl.StupidUtilities
import org.moqui.impl.StupidWebUtilities
import org.moqui.impl.context.ExecutionContextFactoryImpl.WebappInfo
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.screen.ScreenDefinition
import org.moqui.impl.screen.ScreenUrlInfo
import org.moqui.impl.service.ServiceJsonRpcDispatcher
import org.moqui.impl.service.ServiceXmlRpcDispatcher

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

import javax.servlet.ServletContext
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpSession
import java.security.SecureRandom

/** This class is a facade to easily get information from and about the web context. */
class WebFacadeImpl implements WebFacade {
    protected final static Logger logger = LoggerFactory.getLogger(WebFacadeImpl.class)

    // Not using shared root URL cache because causes issues when requests come to server through different hosts/etc:
    // protected static final Map<String, String> webappRootUrlByParms = new HashMap()

    protected ExecutionContextImpl eci
    protected String webappMoquiName
    protected HttpServletRequest request
    protected HttpServletResponse response

    protected Map<String, Object> savedParameters = null
    protected Map<String, Object> multiPartParameters = null
    protected Map<String, Object> jsonParameters = null
    protected Map<String, Object> declaredPathParameters = null

    protected ContextStack parameters = null
    protected Map<String, Object> requestAttributes = null
    protected Map<String, Object> requestParameters = null
    protected Map<String, Object> sessionAttributes = null
    protected Map<String, Object> applicationAttributes = null

    protected Map<String, Object> errorParameters = null

    protected List<String> savedMessages = null
    protected List<String> savedErrors = null
    protected List<ValidationError> savedValidationErrors = null

    WebFacadeImpl(String webappMoquiName, HttpServletRequest request, HttpServletResponse response,
                  ExecutionContextImpl eci) {
        this.eci = eci
        this.webappMoquiName = webappMoquiName
        this.request = request
        this.response = response

        // NOTE: the Visit is not setup here but rather in the MoquiEventListener (for init and destroy)
        request.setAttribute("ec", eci)

        // get any parameters saved to the session from the last request, and clear that session attribute if there
        savedParameters = (Map) request.session.getAttribute("moqui.saved.parameters")
        if (savedParameters != null) request.session.removeAttribute("moqui.saved.parameters")

        errorParameters = (Map) request.session.getAttribute("moqui.error.parameters")
        if (errorParameters != null) request.session.removeAttribute("moqui.error.parameters")

        // get any messages saved to the session, and clear them from the session
        if (session.getAttribute("moqui.message.messages")) {
            savedMessages = (List<String>) session.getAttribute("moqui.message.messages")
            session.removeAttribute("moqui.message.messages")
        }
        if (session.getAttribute("moqui.message.errors")) {
            savedErrors = (List<String>) session.getAttribute("moqui.message.errors")
            session.removeAttribute("moqui.message.errors")
        }
        if (session.getAttribute("moqui.message.validationErrors")) {
            savedValidationErrors = (List<ValidationError>) session.getAttribute("moqui.message.validationErrors")
            session.removeAttribute("moqui.message.validationErrors")
        }

        // if there is a JSON document submitted consider those as parameters too
        String contentType = request.getHeader("Content-Type")
        if (contentType && (contentType.contains("application/json") || contentType.contains("text/json"))) {
            JsonSlurper slurper = new JsonSlurper()
            Object jsonObj = null
            try {
                jsonObj = slurper.parse(new BufferedReader(new InputStreamReader(request.getInputStream(),
                        request.getCharacterEncoding() ?: "UTF-8")))
            } catch (Throwable t) {
                logger.error("Error parsing HTTP request body JSON: ${t.toString()}", t)
                jsonParameters = [_requestBodyJsonParseError:t.getMessage()]
            }
            if (jsonObj instanceof Map) {
                jsonParameters = (Map<String, Object>) jsonObj
            } else if (jsonObj instanceof List) {
                jsonParameters = [_requestBodyJsonList:jsonObj]
            }
            // logger.warn("=========== Got JSON HTTP request body: ${jsonParameters}")
        }

        // if this is a multi-part request, get the data for it
        if (ServletFileUpload.isMultipartContent(request)) {
            multiPartParameters = new HashMap()
            FileItemFactory factory = makeDiskFileItemFactory(request.session.getServletContext())
            ServletFileUpload upload = new ServletFileUpload(factory)

            List<FileItem> items = (List<FileItem>) upload.parseRequest(request)
            List<FileItem> fileUploadList = []
            multiPartParameters.put("_fileUploadList", fileUploadList)

            for (FileItem item in items) {
                if (item.isFormField()) {
                    multiPartParameters.put(item.getFieldName(), item.getString("UTF-8"))
                } else {
                    // put the FileItem itself in the Map to be used by the application code
                    multiPartParameters.put(item.getFieldName(), item)
                    fileUploadList.add(item)

                    /* Stuff to do with the FileItem:
                      - get info about the uploaded file
                        String fieldName = item.getFieldName()
                        String fileName = item.getName()
                        String contentType = item.getContentType()
                        boolean isInMemory = item.isInMemory()
                        long sizeInBytes = item.getSize()

                      - get the bytes in memory
                        byte[] data = item.get()

                      - write the data to a File
                        File uploadedFile = new File(...)
                        item.write(uploadedFile)

                      - get the bytes in a stream
                        InputStream uploadedStream = item.getInputStream()
                        ...
                        uploadedStream.close()
                     */
                }
            }
        }

        // create the session token if needed (protection against CSRF/XSRF attacks; see ScreenRenderImpl)
        String sessionToken = session.getAttribute("moqui.session.token")
        if (!sessionToken) {
            SecureRandom sr = new SecureRandom()
            byte[] randomBytes = new byte[20]
            sr.nextBytes(randomBytes)
            sessionToken = Base64.encodeBase64URLSafeString(randomBytes)
            session.setAttribute("moqui.session.token", sessionToken)
            request.setAttribute("moqui.session.token.created", "true")
        }
    }

    @Override
    @CompileStatic
    String getSessionToken() { return session.getAttribute("moqui.session.token") }

    // ExecutionContextImpl getEci() { eci }
    void runFirstHitInVisitActions() {
        WebappInfo wi = eci.ecfi.getWebappInfo(webappMoquiName)
        if (wi.firstHitInVisitActions) wi.firstHitInVisitActions.run(eci)
    }
    void runBeforeRequestActions() {
        WebappInfo wi = eci.ecfi.getWebappInfo(webappMoquiName)
        if (wi.beforeRequestActions) wi.beforeRequestActions.run(eci)
    }
    void runAfterRequestActions() {
        WebappInfo wi = eci.ecfi.getWebappInfo(webappMoquiName)
        if (wi.afterRequestActions) wi.afterRequestActions.run(eci)
    }
    void runAfterLoginActions() {
        WebappInfo wi = eci.ecfi.getWebappInfo(webappMoquiName)
        if (wi.afterLoginActions) wi.afterLoginActions.run(eci)
    }
    void runBeforeLogoutActions() {
        WebappInfo wi = eci.ecfi.getWebappInfo(webappMoquiName)
        if (wi.beforeLogoutActions) wi.beforeLogoutActions.run(eci)
    }

    @CompileStatic
    void saveScreenHistory(ScreenUrlInfo.UrlInstance urlInstanceOrig) {
        ScreenUrlInfo sui = urlInstanceOrig.sui
        ScreenDefinition targetScreen = urlInstanceOrig.sui.targetScreen

        // don't save standalone screens
        if (sui.lastStandalone || targetScreen.isStandalone()) return
        // don't save transition requests, just screens
        if (urlInstanceOrig.getTargetTransition() != null) return

        LinkedList<Map> screenHistoryList = (LinkedList<Map>) session.getAttribute("moqui.screen.history")
        if (screenHistoryList == null) {
            screenHistoryList = new LinkedList<Map>()
            session.setAttribute("moqui.screen.history", screenHistoryList)
        }

        ScreenUrlInfo.UrlInstance urlInstance = urlInstanceOrig.cloneUrlInstance()
        // ignore the page index for history
        urlInstance.getParameterMap().remove("pageIndex")
        // logger.warn("======= parameters: ${urlInstance.getParameterMap()}")
        String urlWithParams = urlInstance.getUrlWithParams()
        // logger.warn("======= urlWithParams: ${urlWithParams}")

        // if is the same as last screen skip it
        Map firstItem = screenHistoryList.size() > 0 ? screenHistoryList.get(0) : null
        if (firstItem != null && firstItem.url == urlWithParams) return

        String targetMenuName = targetScreen.getDefaultMenuName()
        // may need a better way to identify login screens, for now just look for "Login"
        if (targetMenuName == "Login") return


        StringBuilder nameBuilder = new StringBuilder()
        // append parent screen name
        ScreenDefinition parentScreen = sui.getParentScreen()
        if (parentScreen != null) {
            if (parentScreen.getLocation() != sui.rootSd.getLocation())
                nameBuilder.append(parentScreen.getDefaultMenuName()).append(' - ')
        }
        // append target screen name
        if (targetMenuName.contains('${')) {
            nameBuilder.append(eci.getResource().expand(targetMenuName, targetScreen.getLocation()))
        } else {
            nameBuilder.append(targetMenuName)
            // append parameter values
            Map parameters = urlInstance.getParameterMap()
            if (parameters) {
                nameBuilder.append(' (')
                int pCount = 0
                Iterator<Map.Entry<String, String>> entryIter = parameters.entrySet().iterator()
                while (entryIter.hasNext() && pCount < 2) {
                    Map.Entry<String, String> entry = entryIter.next()
                    if (entry.key.contains("_op")) continue
                    if (entry.key.contains("_not")) continue
                    if (entry.key.contains("_ic")) continue
                    if (!entry.value.trim()) continue
                    nameBuilder.append(entry.value)
                    pCount++
                    if (entryIter.hasNext() && pCount < 2) nameBuilder.append(', ')
                }
                nameBuilder.append(')')
            }
        }

        // remove existing item(s) from list with same URL
        Iterator<Map> screenHistoryIter = screenHistoryList.iterator()
        while (screenHistoryIter.hasNext()) {
            Map screenHistory = screenHistoryIter.next()
            if (screenHistory.url == urlWithParams) screenHistoryIter.remove()
        }

        // add to history list
        screenHistoryList.addFirst([name:nameBuilder.toString(), url:urlWithParams, image:sui.menuImage,
                                    imageType:sui.menuImageType, screenLocation:targetScreen.getLocation()])

        // trim the list if needed; keep 40, whatever uses it may display less
        while (screenHistoryList.size() > 40) screenHistoryList.removeLast()
    }

    @Override
    @CompileStatic
    List<Map> getScreenHistory() { return (LinkedList<Map>) session.getAttribute("moqui.screen.history") }


    @Override
    @CompileStatic
    String getRequestUrl() {
        StringBuilder requestUrl = new StringBuilder()
        requestUrl.append(request.getScheme())
        requestUrl.append("://" + request.getServerName())
        if (request.getServerPort() != 80 && request.getServerPort() != 443) requestUrl.append(":" + request.getServerPort())
        requestUrl.append(request.getRequestURI())
        if (request.getQueryString()) requestUrl.append("?" + request.getQueryString())
        return requestUrl.toString()
    }

    @CompileStatic
    void addDeclaredPathParameter(String name, String value) {
        if (declaredPathParameters == null) declaredPathParameters = new HashMap()
        declaredPathParameters.put(name, value)
    }

    @CompileStatic
    List<String> getSavedMessages() { return savedMessages }
    @CompileStatic
    List<String> getSavedErrors() { return savedErrors }
    @CompileStatic
    List<ValidationError> getSavedValidationErrors() { return savedValidationErrors }

    @Override
    @CompileStatic
    Map<String, Object> getParameters() {
        // NOTE: no blocking in these methods because the WebFacadeImpl is created for each thread

        // only create when requested, then keep for additional requests
        if (parameters != null) return parameters

        // Uses the approach of creating a series of this objects wrapping the other non-Map attributes/etc instead of
        // copying everything from the various places into a single combined Map; this should be much faster to create
        // and only slightly slower when running.
        ContextStack cs = new ContextStack()
        cs.push(getRequestParameters())
        cs.push(getApplicationAttributes())
        cs.push(getSessionAttributes())
        cs.push(getRequestAttributes())
        // add an extra Map for anything added so won't go in  request attributes (can put there explicitly if desired)
        cs.push()
        parameters = cs
        return parameters
    }

    @Override
    @CompileStatic
    HttpServletRequest getRequest() { return request }
    @Override
    @CompileStatic
    Map<String, Object> getRequestAttributes() {
        if (requestAttributes != null) return requestAttributes
        requestAttributes = new StupidWebUtilities.RequestAttributeMap(request)
        return requestAttributes
    }
    @Override
    @CompileStatic
    Map<String, Object> getRequestParameters() {
        if (requestParameters != null) return requestParameters

        ContextStack cs = new ContextStack()
        if (savedParameters) cs.push(savedParameters)
        if (multiPartParameters) cs.push(multiPartParameters)
        if (jsonParameters) cs.push(jsonParameters)
        if (declaredPathParameters) cs.push(declaredPathParameters)
        cs.push((Map<String, Object>) request.getParameterMap())
        cs.push(StupidWebUtilities.getPathInfoParameterMap(request.getPathInfo()))

        // NOTE: the CanonicalizeMap cleans up character encodings, and unwraps lists of values with a single entry
        requestParameters = new StupidWebUtilities.CanonicalizeMap(cs)
        return requestParameters
    }

    @Override
    HttpServletResponse getResponse() { return response }

    @Override
    HttpSession getSession() { return request.getSession(true) }
    @Override
    @CompileStatic
    Map<String, Object> getSessionAttributes() {
        if (sessionAttributes) return sessionAttributes
        sessionAttributes = new StupidWebUtilities.SessionAttributeMap(getSession())
        return sessionAttributes
    }

    @Override
    ServletContext getServletContext() { return getSession().getServletContext() }
    @Override
    @CompileStatic
    Map<String, Object> getApplicationAttributes() {
        if (applicationAttributes) return applicationAttributes
        applicationAttributes = new StupidWebUtilities.ServletContextAttributeMap(getSession().getServletContext())
        return applicationAttributes
    }
    @Override
    @CompileStatic
    String getWebappRootUrl(boolean requireFullUrl, Boolean useEncryption) {
        return getWebappRootUrl(this.webappMoquiName, null, requireFullUrl, useEncryption, eci)
    }

    @CompileStatic
    static String getWebappRootUrl(String webappName, String servletContextPath, boolean requireFullUrl, Boolean useEncryption, ExecutionContextImpl eci) {
        WebFacade webFacade = eci.getWeb()
        HttpServletRequest request = webFacade?.getRequest()
        boolean requireEncryption = useEncryption == null && request != null ? request.isSecure() : useEncryption
        boolean needFullUrl = requireFullUrl || request == null ||
                (requireEncryption && !request.isSecure()) || (!requireEncryption && request.isSecure())

        /* Not using shared root URL cache because causes issues when requests come to server through different hosts/etc:
        String cacheKey = webappName + servletContextPath + needFullUrl.toString() + requireEncryption.toString()
        String cachedRootUrl = webappRootUrlByParms.get(cacheKey)
        if (cachedRootUrl != null) return cachedRootUrl

        String urlValue = makeWebappRootUrl(webappName, servletContextPath, eci, webFacade, requireEncryption, needFullUrl)
        webappRootUrlByParms.put(cacheKey, urlValue)
        return urlValue
         */

        // cache the root URLs just within the request, common to generate various URLs in a single request
        String cacheKey = null
        if (request != null) {
            cacheKey = webappName + servletContextPath + needFullUrl.toString() + requireEncryption.toString()
            String cachedRootUrl = request.getAttribute(cacheKey)
            if (cachedRootUrl != null) return cachedRootUrl
        }

        String urlValue = makeWebappRootUrl(webappName, servletContextPath, eci, webFacade, requireEncryption, needFullUrl)
        if (cacheKey) request.setAttribute(cacheKey, urlValue)
        return urlValue
    }
    static String makeWebappRootUrl(String webappName, String servletContextPath, ExecutionContextImpl eci, WebFacade webFacade,
                                    boolean requireEncryption, boolean needFullUrl) {

        Node webappNode = (Node) eci.ecfi.confXmlRoot."webapp-list"[0]."webapp".find({ it.@name == webappName })
        StringBuilder urlBuilder = new StringBuilder()
        // build base from conf
        if (needFullUrl && webappNode) {
            if (requireEncryption && webappNode."@https-enabled" != "false") {
                urlBuilder.append("https://")
                if (webappNode."@https-host") {
                    urlBuilder.append(webappNode."@https-host")
                } else {
                    if (webFacade != null) {
                        String hostName = null
                        try { hostName = new URL(webFacade.getRequest().getRequestURL().toString()).getHost() }
                        catch (Exception e) { /* ignore it, default to getServerName() result */ }
                        if (!hostName) hostName = webFacade.getRequest().getServerName()
                        urlBuilder.append(hostName)
                    } else {
                        // uh-oh, no web context, default to localhost
                        urlBuilder.append("localhost")
                    }
                }
                String httpsPort = webappNode."@https-port"
                // try the local port; this won't work when switching from http to https, conf required for that
                if (!httpsPort && webFacade != null && webFacade.getRequest().isSecure())
                    httpsPort = webFacade.getRequest().getServerPort() as String
                if (httpsPort && httpsPort != "443") urlBuilder.append(":").append(httpsPort)
            } else {
                urlBuilder.append("http://")
                if (webappNode."@http-host") {
                    urlBuilder.append(webappNode."@http-host")
                } else {
                    if (webFacade) {
                        String hostName = null
                        try {
                            hostName = new URL(webFacade.getRequest().getRequestURL().toString()).getHost()
                            // logger.info("Got hostName [${hostName}] from getRequestURL [${webFacade.getRequest().getRequestURL()}]")
                        } catch (Exception e) {
                            /* ignore it, default to getServerName() result */
                            logger.trace("Error getting hostName from getRequestURL: ", e)
                        }
                        if (!hostName) {
                            hostName = webFacade.getRequest().getServerName()
                            // logger.info("Got hostName [${hostName}] from getServerName")
                        }
                        urlBuilder.append(hostName)
                    } else {
                        // uh-oh, no web context, default to localhost
                        urlBuilder.append("localhost")
                        logger.warn("No webFacade in place, defaulting to localhost for hostName")
                    }
                }
                String httpPort = webappNode."@http-port"
                // try the server port; this won't work when switching from https to http, conf required for that
                if (!httpPort && webFacade != null && !webFacade.getRequest().isSecure())
                    httpPort = webFacade.getRequest().getServerPort() as String
                if (httpPort && httpPort != "80") urlBuilder.append(":").append(httpPort)
            }
            urlBuilder.append("/")
        } else {
            // can't get these settings, hopefully a URL from the root will do
            urlBuilder.append("/")
        }

        // add servletContext.contextPath
        if (!servletContextPath && webFacade)
            servletContextPath = webFacade.getServletContext().getContextPath()
        if (servletContextPath) {
            if (servletContextPath.startsWith("/")) servletContextPath = servletContextPath.substring(1)
            urlBuilder.append(servletContextPath)
        }

        // make sure we don't have a trailing slash
        if (urlBuilder.charAt(urlBuilder.length()-1) == (char) '/') urlBuilder.deleteCharAt(urlBuilder.length()-1)

        String urlValue = urlBuilder.toString()
        return urlValue
    }


    @Override
    Map<String, Object> getErrorParameters() { return errorParameters }

    @Override
    @CompileStatic
    void sendJsonResponse(Object responseObj) {
        String jsonStr
        if (responseObj instanceof CharSequence) {
            jsonStr = responseObj.toString()
        } else {
            if (eci.message.messages) {
                if (responseObj == null) {
                    responseObj = [messages:eci.message.getMessagesString()] as Map<String, Object>
                } else if (responseObj instanceof Map && !responseObj.containsKey("messages")) {
                    Map responseMap = new HashMap()
                    responseMap.putAll(responseObj)
                    responseMap.put("messages", eci.message.getMessagesString())
                    responseObj = responseMap
                }
            }

            if (eci.getMessage().hasError()) {
                JsonBuilder jb = new JsonBuilder()
                // if the responseObj is a Map add all of it's data
                if (responseObj instanceof Map) {
                    // only add an errors if it is not a jsonrpc response (JSON RPC has it's own error handling)
                    if (!responseObj.containsKey("jsonrpc")) {
                        Map responseMap = new HashMap()
                        responseMap.putAll(responseObj)
                        responseMap.put("errors", eci.message.errorsString)
                        responseObj = responseMap
                    }
                    jb.call(responseObj)
                } else if (responseObj != null) {
                    logger.error("Error found when sending JSON string but JSON object is not a Map so not sending: ${eci.message.errorsString}")
                    jb.call(responseObj)
                }

                jsonStr = jb.toString()
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
            } else if (responseObj != null) {
                // logger.warn("========== Sending JSON for object: ${responseObj}")
                JsonBuilder jb = new JsonBuilder()
                if (responseObj instanceof Map) {
                    jb.call((Map) responseObj)
                } else if (responseObj instanceof List) {
                    jb.call((List) responseObj)
                } else {
                    jb.call((Object) responseObj)
                }
                jsonStr = jb.toPrettyString()
                response.setStatus(HttpServletResponse.SC_OK)
            } else {
                jsonStr = ""
                response.setStatus(HttpServletResponse.SC_OK)
            }
        }

        if (!jsonStr) return

        // logger.warn("========== Sending JSON string: ${jsonStr}")
        response.setContentType("application/json")
        // NOTE: String.length not correct for byte length
        String charset = response.getCharacterEncoding() ?: "UTF-8"
        int length = jsonStr.getBytes(charset).length
        response.setContentLength(length)

        try {
            response.writer.write(jsonStr)
            response.writer.flush()
            if (logger.infoEnabled) {
                Long startTime = (Long) requestAttributes.get("moquiRequestStartTime")
                String timeMsg = ""
                if (startTime) timeMsg = "in [${(System.currentTimeMillis()-startTime)/1000}] seconds"
                logger.info("Sent JSON response of length [${length}] with [${charset}] encoding ${timeMsg} for ${request.getMethod()} request to ${request.getPathInfo()}")
            }
        } catch (IOException e) {
            logger.error("Error sending JSON string response", e)
        }
    }

    @Override
    @CompileStatic
    void sendTextResponse(String text) { sendTextResponse(text, "text/plain") }
    @Override
    @CompileStatic
    void sendTextResponse(String text, String contentType) {
        String responseText
        if (eci.getMessage().hasError()) {
            responseText = eci.message.errorsString
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
        } else {
            responseText = text
            response.setStatus(HttpServletResponse.SC_OK)
        }

        response.setContentType(contentType)
        // NOTE: String.length not correct for byte length
        String charset = response.getCharacterEncoding() ?: "UTF-8"
        int length = responseText ? responseText.getBytes(charset).length : 0
        response.setContentLength(length)

        try {
            if (responseText) response.writer.write(responseText)
            response.writer.flush()
            if (logger.infoEnabled) {
                Long startTime = (Long) requestAttributes.get("moquiRequestStartTime")
                String timeMsg = ""
                if (startTime) timeMsg = "in [${(System.currentTimeMillis()-startTime)/1000}] seconds"
                logger.info("Sent text (${contentType}) response of length [${length}] with [${charset}] encoding ${timeMsg} for ${request.getMethod()} request to ${request.getPathInfo()}")
            }
        } catch (IOException e) {
            logger.error("Error sending text response", e)
        }
    }

    @Override
    @CompileStatic
    void sendResourceResponse(String location) { sendResourceResponse(location, false) }
    @CompileStatic
    void sendResourceResponse(String location, boolean inline) {
        ResourceReference rr = eci.resource.getLocationReference(location)
        if (rr == null) throw new IllegalArgumentException("Resource not found at: ${location}")
        response.setContentType(rr.contentType)
        if (inline) response.addHeader("Content-Disposition", "inline")
        else response.addHeader("Content-Disposition", "attachment; filename=\"${rr.getFileName()}\"; filename*=utf-8''${StupidUtilities.encodeAsciiFilename(rr.getFileName())}")
        InputStream is = rr.openStream()
        try {
            OutputStream os = response.outputStream
            try {
                int totalLen = StupidUtilities.copyStream(is, os)
                logger.info("Streamed ${totalLen} bytes from contentLocation ${location}")
            } finally {
                os.close()
            }
        } finally {
            is.close()
        }
    }

    @Override
    @CompileStatic
    void handleXmlRpcServiceCall() { new ServiceXmlRpcDispatcher(eci).dispatch(request, response) }

    @Override
    @CompileStatic
    void handleJsonRpcServiceCall() { new ServiceJsonRpcDispatcher(eci).dispatch(request, response) }

    @Override
    @CompileStatic
    void handleEntityRestCall(List<String> extraPathNameList) {
        ContextStack parmStack = (ContextStack) getParameters()

        // check for parsing error, send a 400 response
        if (parmStack._requestBodyJsonParseError) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, (String) parmStack._requestBodyJsonParseError)
            return
        }

        // make sure a user is logged in, screen/etc that calls will generally be configured to not require auth
        if (!eci.getUser().getUsername()) {
            // if there was a login error there will be a MessageFacade error message
            String errorMessage = eci.message.errorsString
            if (!errorMessage) errorMessage = "Authentication required for entity REST operations"
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, errorMessage)
            return
        }

        try {
            // logger.warn("====== parameters: ${parmStack.toString()}")
            // if _requestBodyJsonList do multiple calls
            if (parmStack._requestBodyJsonList) {
                // TODO: Consider putting all of this in a transaction for non-find operations (currently each is run in
                // TODO:     a separate transaction); or handle errors per-row instead of blowing up the whole request
                List responseList = []
                for (Object bodyListObj in parmStack._requestBodyJsonList) {
                    if (!(bodyListObj instanceof Map)) {
                        String errMsg = "If request body JSON is a list/array it must contain only object/map values, found non-map entry of type ${bodyListObj.getClass().getName()} with value: ${bodyListObj}"
                        logger.warn(errMsg)
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST, errMsg)
                        return
                    }
                    // logger.warn("========== REST ${request.getMethod()} ${request.getPathInfo()} ${extraPathNameList}; body list object: ${bodyListObj}")
                    parmStack.push()
                    parmStack.putAll((Map) bodyListObj)
                    Object responseObj = eci.getEntity().rest(request.getMethod(), extraPathNameList, parmStack)
                    responseList.add(responseObj ?: [:])
                    parmStack.pop()
                }
                sendJsonResponse(responseList)
            } else {
                long startTime = System.currentTimeMillis()
                Object responseObj = eci.getEntity().rest(request.getMethod(), extraPathNameList, parmStack)
                long endTime = System.currentTimeMillis()
                response.addIntHeader('X-Run-Time-ms', (endTime - startTime) as int)

                if (parmStack.xTotalCount != null) response.addIntHeader('X-Total-Count', parmStack.xTotalCount as int)
                if (parmStack.xPageIndex != null) response.addIntHeader('X-Page-Index', parmStack.xPageIndex as int)
                if (parmStack.xPageSize != null) response.addIntHeader('X-Page-Size', parmStack.xPageSize as int)
                if (parmStack.xPageMaxIndex != null) response.addIntHeader('X-Page-Max-Index', parmStack.xPageMaxIndex as int)
                if (parmStack.xPageRangeLow != null) response.addIntHeader('X-Page-Range-Low', parmStack.xPageRangeLow as int)
                if (parmStack.xPageRangeHigh != null) response.addIntHeader('X-Page-Range-High', parmStack.xPageRangeHigh as int)

                // NOTE: This will always respond with 200 OK, consider using 201 Created (for successful POST, create PUT)
                //     and 204 No Content (for DELETE and other when no content is returned)
                sendJsonResponse(responseObj)
            }
        } catch (ArtifactAuthorizationException e) {
            // SC_UNAUTHORIZED 401 used when authc/login fails, use SC_FORBIDDEN 403 for authz failures
            logger.warn("REST Access Forbidden (no authz): " + e.message)
            response.sendError(HttpServletResponse.SC_FORBIDDEN, e.message)
        } catch (ArtifactTarpitException e) {
            logger.warn("REST Too Many Requests (tarpit): " + e.message)
            if (e.getRetryAfterSeconds()) response.addIntHeader("Retry-After", e.getRetryAfterSeconds())
            // NOTE: there is no constant on HttpServletResponse for 429; see RFC 6585 for details
            response.sendError(429, e.message)
        } catch (EntityNotFoundException e) {
            logger.warn((String) "REST Entity Not Found: " + e.getMessage(), e)
            // send bad request (400), reserve 404 Not Found for records that don't exist
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.message)
        } catch (EntityValueNotFoundException e) {
            logger.warn("REST Entity Value Not Found: " + e.getMessage())
            // record doesn't exist, send 404 Not Found
            response.sendError(HttpServletResponse.SC_NOT_FOUND, e.message)
        } catch (Throwable t) {
            String errorMessage = t.toString()
            if (eci.message.hasError()) {
                String errorsString = eci.message.errorsString
                logger.error(errorsString, t)
                errorMessage = errorMessage + ' ' + errorsString
            }
            logger.warn((String) "General error in entity REST: " + t.toString(), t)
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorMessage)
        }
    }

    @Override
    @CompileStatic
    void handleEntityRestSchema(List<String> extraPathNameList, String schemaUri, String linkPrefix, String schemaLinkPrefix) {
        // make sure a user is logged in, screen/etc that calls will generally be configured to not require auth
        if (!eci.getUser().getUsername()) {
            // if there was a login error there will be a MessageFacade error message
            String errorMessage = eci.message.errorsString
            if (!errorMessage) errorMessage = "Authentication required for entity REST schema"
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, errorMessage)
            return
        }

        EntityFacadeImpl efi = eci.getEcfi().getEntityFacade()

        if (extraPathNameList.size() < 1) {
            List allRefList = []
            Map definitionsMap = [:]
            definitionsMap.put('paginationParameters', EntityDefinition.paginationParameters)
            Map rootMap = ['$schema':'http://json-schema.org/draft-04/hyper-schema#', title:'Moqui Entity REST API',
                    anyOf:allRefList, definitions:definitionsMap]
            if (schemaUri) rootMap.put('id', schemaUri)

            Set<String> entityNameSet = efi.getAllNonViewEntityNames()
            for (String entityName in entityNameSet) {
                EntityDefinition ed = efi.getEntityDefinition(entityName)
                String refName = ed.getShortAlias() ?: ed.getFullEntityName()
                allRefList.add(['$ref':"#/definitions/${refName}"])

                Map schema = ed.getJsonSchema(false, null, schemaUri, linkPrefix, schemaLinkPrefix)
                definitionsMap.put(refName, schema)
            }

            JsonBuilder jb = new JsonBuilder()
            jb.call(rootMap)
            String jsonStr = jb.toPrettyString()

            sendTextResponse(jsonStr, "application/schema+json")
        } else {
            String entityName = extraPathNameList.get(0)
            if (entityName.endsWith(".json")) entityName = entityName.substring(0, entityName.length() - 5)
            try {
                EntityDefinition ed = efi.getEntityDefinition(entityName)
                if (ed == null) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No entity found with name or alias [${entityName}]")
                    return
                }

                Map schema = ed.getJsonSchema(true, null, schemaUri, linkPrefix, schemaLinkPrefix)
                // TODO: support array wrapper (different URL? suffix?) with [type:'array', items:schema]

                // sendJsonResponse(schema)
                JsonBuilder jb = new JsonBuilder()
                jb.call(schema)
                String jsonStr = jb.toPrettyString()

                sendTextResponse(jsonStr, "application/schema+json")
            } catch (EntityNotFoundException e) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No entity found with name or alias [${entityName}]")
            }
        }
    }

    @Override
    @CompileStatic
    void handleEntityRestRaml(List<String> extraPathNameList, String linkPrefix, String schemaLinkPrefix) {
        // make sure a user is logged in, screen/etc that calls will generally be configured to not require auth
        if (!eci.getUser().getUsername()) {
            // if there was a login error there will be a MessageFacade error message
            String errorMessage = eci.message.errorsString
            if (!errorMessage) errorMessage = "Authentication required for entity REST schema"
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, errorMessage)
            return
        }

        EntityFacadeImpl efi = eci.getEcfi().getEntityFacade()

        List<Map> schemasList = []
        Map<String, Object> rootMap = [title:'Moqui Entity REST API', version:'v1', baseUri:linkPrefix,
                                       mediaType:'application/json', schemas:schemasList] as Map<String, Object>
        rootMap.put('traits', [[paged:[queryParameters:EntityDefinition.ramlPaginationParameters]]])

        Set<String> entityNameSet = efi.getAllNonViewEntityNames()
        for (String entityName in entityNameSet) {
            EntityDefinition ed = efi.getEntityDefinition(entityName)
            String refName = ed.getShortAlias() ?: ed.getFullEntityName()
            schemasList.add([(refName):"!include ${schemaLinkPrefix}/${refName}.json".toString()])

            Map ramlApi = ed.getRamlApi()
            rootMap.put('/' + refName, ramlApi)
        }

        DumperOptions options = new DumperOptions()
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
        // default: options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN)
        options.setPrettyFlow(true)
        Yaml yaml = new Yaml(options)
        String yamlString = yaml.dump(rootMap)
        // add beginning line "#%RAML 0.8", more efficient way to do this?
        yamlString = "#%RAML 0.8\n" + yamlString

        sendTextResponse(yamlString, "application/raml+yaml")
    }


    void saveScreenLastInfo(String screenPath, Map parameters) {
        session.setAttribute("moqui.screen.last.path", screenPath ?: request.getPathInfo())
        session.setAttribute("moqui.screen.last.parameters", parameters ?: new HashMap(getRequestParameters()))
    }

    String getRemoveScreenLastPath() {
        String path = session.getAttribute("moqui.screen.last.path")
        session.removeAttribute("moqui.screen.last.path")
        return path
    }
    Map getSavedParameters() { return (Map) session.getAttribute("moqui.saved.parameters") }
    void removeScreenLastParameters(boolean moveToSaved) {
        if (moveToSaved) session.setAttribute("moqui.saved.parameters", session.getAttribute("moqui.screen.last.parameters"))
        session.removeAttribute("moqui.screen.last.parameters")
    }

    void saveMessagesToSession() {
        if (eci.message.messages) session.setAttribute("moqui.message.messages", eci.message.messages)
        if (eci.message.errors) session.setAttribute("moqui.message.errors", eci.message.errors)
        if (eci.message.validationErrors) session.setAttribute("moqui.message.validationErrors", eci.message.validationErrors)
    }

    /** Save passed parameters Map to a Map in the moqui.saved.parameters session attribute */
    void saveParametersToSession(Map parameters) {
        Map parms = new HashMap()
        Map currentSavedParameters = (Map) request.session.getAttribute("moqui.saved.parameters")
        if (currentSavedParameters) parms.putAll(currentSavedParameters)
        if (parameters) parms.putAll(parameters)
        session.setAttribute("moqui.saved.parameters", parms)
    }
    /** Save request parameters and attributes to a Map in the moqui.saved.parameters session attribute */
    void saveRequestParametersToSession() {
        Map parms = new HashMap()
        Map currentSavedParameters = (Map) request.session.getAttribute("moqui.saved.parameters")
        if (currentSavedParameters) parms.putAll(currentSavedParameters)
        if (requestParameters) parms.putAll(requestParameters)
        if (requestAttributes) parms.putAll(requestAttributes)
        session.setAttribute("moqui.saved.parameters", parms)
    }

    /** Save request parameters and attributes to a Map in the moqui.error.parameters session attribute */
    void saveErrorParametersToSession() {
        Map parms = new HashMap()
        if (requestParameters) parms.putAll(requestParameters)
        if (requestAttributes) parms.putAll(requestAttributes)
        session.setAttribute("moqui.error.parameters", parms)
    }

    static DiskFileItemFactory makeDiskFileItemFactory(ServletContext context) {
        // NOTE: consider keeping this factory somewhere to be more efficient, if it even makes a difference...
        File repository = new File(System.getProperty("moqui.runtime") + "/tmp")
        if (!repository.exists()) repository.mkdir()

        DiskFileItemFactory factory = new DiskFileItemFactory(DiskFileItemFactory.DEFAULT_SIZE_THRESHOLD, repository)

        // TODO: this was causing files to get deleted before the upload was streamed... need to figure out something else
        //FileCleaningTracker fileCleaningTracker = FileCleanerCleanup.getFileCleaningTracker(context)
        //factory.setFileCleaningTracker(fileCleaningTracker)
        return factory
    }
}
