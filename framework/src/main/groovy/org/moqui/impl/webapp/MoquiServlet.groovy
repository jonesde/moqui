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
package org.moqui.impl.webapp

import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.ServletException

import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class MoquiServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiServlet.class)

    MoquiServlet() { super(); }

    @Override
    void doPost(HttpServletRequest request, HttpServletResponse response) { doScreenRequest(request, response) }

    @Override
    void doGet(HttpServletRequest request, HttpServletResponse response) { doScreenRequest(request, response) }

    @Override
    void doPut(HttpServletRequest request, HttpServletResponse response) { doScreenRequest(request, response) }

    @Override
    void doDelete(HttpServletRequest request, HttpServletResponse response) { doScreenRequest(request, response) }

    void doScreenRequest(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        ExecutionContextFactory executionContextFactory =
                (ExecutionContextFactory) getServletContext().getAttribute("executionContextFactory")
        String moquiWebappName = getServletContext().getInitParameter("moqui-name")

        String pathInfo = request.getPathInfo()
        long startTime = System.currentTimeMillis()

        if (logger.traceEnabled) logger.trace("Start request to [${pathInfo}] at time [${startTime}] in session [${request.session.id}] thread [${Thread.currentThread().id}:${Thread.currentThread().name}]")

        ExecutionContext ec = executionContextFactory.getExecutionContext()
        if (!request.characterEncoding) request.setCharacterEncoding("UTF-8")
        ec.initWebFacade(moquiWebappName, request, response)

        /** NOTE to set render settings manually do something like this, but it is not necessary to set these things
         * for a web page render because if we call render(request, response) it can figure all of this out as defaults
         *
         * ScreenRender render = ec.screen.makeRender().webappName(webappMoquiName).renderMode("html")
         *         .rootScreenFromHost(request.getServerName()).screenPath(pathInfo.split("/") as List)
         */

        try {
            ec.screen.makeRender().render(request, response)
        } catch (ArtifactAuthorizationException e) {
            logger.warn("Web Access Unauthorized: " + e.message)
            response.sendError(401, e.message)
        } catch (ScreenResourceNotFoundException e) {
            logger.warn("Web Resource Not Found: " + e.message)
            response.sendError(404, e.message)
        } catch (Throwable t) {
            if (ec.message.hasError()) {
                String errorsString = ec.message.errorsString
                logger.error(errorsString, t)
                response.sendError(500, errorsString)
            } else {
                throw t
            }
        }
        finally {
            // make sure everything is cleaned up
            ec.destroy()
        }

        if (logger.infoEnabled) logger.info("Finished request to [${pathInfo}] of content type [${response.getContentType()}] in [${(System.currentTimeMillis()-startTime)/1000}] seconds in session [${request.session.id}] thread [${Thread.currentThread().id}:${Thread.currentThread().name}]")

        /* this is here just for kicks, uncomment to log a list of all artifacts hit/used in the screen render
        StringBuilder hits = new StringBuilder()
        hits.append("Artifacts hit in this request: ")
        for (def aei in ec.artifactExecution.history) hits.append("\n").append(aei)
        logger.info(hits.toString())
         */
    }
}
