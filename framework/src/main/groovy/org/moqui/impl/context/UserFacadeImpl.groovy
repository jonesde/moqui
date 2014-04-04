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
package org.moqui.impl.context

import org.moqui.BaseException
import org.moqui.context.NotificationMessage
import org.moqui.entity.EntityCondition

import java.sql.Timestamp
import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpSession

import org.apache.shiro.authc.AuthenticationException
import org.apache.shiro.authc.UsernamePasswordToken
import org.apache.shiro.subject.Subject
import org.apache.shiro.web.subject.WebSubjectContext
import org.apache.shiro.web.subject.support.DefaultWebSubjectContext
import org.apache.shiro.web.session.HttpServletSession

import org.moqui.context.UserFacade
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityList
import org.moqui.impl.entity.EntityListImpl
import org.apache.shiro.subject.support.DefaultSubjectContext

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class UserFacadeImpl implements UserFacade {
    protected final static Logger logger = LoggerFactory.getLogger(UserFacadeImpl.class)
    protected final static Set<String> allUserGroupIdOnly = new HashSet(["ALL_USERS"])

    protected ExecutionContextImpl eci
    protected Timestamp effectiveTime = null

    protected Deque<String> usernameStack = new LinkedList()
    // keep a reference to a UserAccount for performance reasons, avoid repeated cached queries
    protected EntityValue internalUserAccount = null
    protected Set<String> internalUserGroupIdSet = null
    protected EntityList internalArtifactTarpitCheckList = null
    protected EntityList internalArtifactAuthzCheckList = null
    protected Locale localeCache = null

    // these are used only when there is no logged in user
    protected Locale noUserLocale = null
    protected TimeZone noUserTimeZone = null
    protected String noUserCurrencyUomId = null
    // if one of these is set before login, set it on the account on login? probably best not...

    protected Calendar calendarForTzLcOnly = null

    /** This is set instead of adding _NA_ user as logged in to pass authc tests but not generally behave as if a user is logged in */
    protected boolean loggedInAnonymous = false

    /** The Shiro Subject (user) */
    protected Subject currentUser = null

    // there may be non-web visits, so keep a copy of the visitId here
    protected String visitId = null

    // we mostly want this for the Locale default, and may be useful for other things
    protected HttpServletRequest request = null

    UserFacadeImpl(ExecutionContextImpl eci) {
        this.eci = eci
    }

    protected void clearPerUserValues() {
        localeCache = null
        calendarForTzLcOnly = null
    }

    void initFromHttpRequest(HttpServletRequest request, HttpServletResponse response) {
        this.request = request
        HttpSession session = request.getSession()

        WebSubjectContext wsc = new DefaultWebSubjectContext()
        wsc.setServletRequest(request); wsc.setServletResponse(response)
        wsc.setSession(new HttpServletSession(session, request.getServerName()))
        currentUser = eci.getEcfi().getSecurityManager().createSubject(wsc)

        if (currentUser.authenticated) {
            // effectively login the user
            String userId = (String) currentUser.principal
            // better not to do this, if there was a user before this init leave it for history/debug: if (this.userIdStack) this.userIdStack.pop()
            if (this.usernameStack.size() == 0 || this.usernameStack.peekFirst() != userId) {
                this.usernameStack.addFirst(userId)
                this.internalUserAccount = null
                this.internalUserGroupIdSet = null
                this.internalArtifactTarpitCheckList = null
                this.internalArtifactAuthzCheckList = null
            }
            if (logger.traceEnabled) logger.trace("For new request found user [${userId}] in the session; userIdStack is [${this.usernameStack}]")
        } else {
            if (logger.traceEnabled) logger.trace("For new request NO user authenticated in the session; userIdStack is [${this.usernameStack}]")
        }

        // check for HTTP Basic Authorization for Authentication purposes
        // NOTE: do this even if there is another user logged in, will go on stack
        String authzHeader = request.getHeader("Authorization")
        if (authzHeader && authzHeader.substring(0, 6).equals("Basic ")) {
            String basicAuthEncoded = authzHeader.substring(6).trim()
            String basicAuthAsString = new String(basicAuthEncoded.decodeBase64())
            if (basicAuthAsString.indexOf(":") > 0) {
                String username = basicAuthAsString.substring(0, basicAuthAsString.indexOf(":"))
                String password = basicAuthAsString.substring(basicAuthAsString.indexOf(":") + 1)
                this.loginUser(username, password, null)
            } else {
                logger.warn("For HTTP Basic Authorization got bad credentials string. Base64 encoded is [${basicAuthEncoded}] and after decoding is [${basicAuthAsString}].")
            }
        } else {
            // try the Moqui-specific parameters for instant login
            // if we have credentials coming in anywhere other than URL parameters, try logging in
            String authUsername = null
            String authPassword = null
            String authTenantId = null
            Map multiPartParameters = eci.webFacade.multiPartParameters
            Map jsonParameters = eci.webFacade.jsonParameters
            if (multiPartParameters && multiPartParameters.authUsername && multiPartParameters.authPassword) {
                authUsername = multiPartParameters.authUsername
                authPassword = multiPartParameters.authPassword
                authTenantId = multiPartParameters.authTenantId
            } else if (jsonParameters && jsonParameters.authUsername && jsonParameters.authPassword) {
                authUsername = jsonParameters.authUsername
                authPassword = jsonParameters.authPassword
                authTenantId = jsonParameters.authTenantId
            } else if (!request.getQueryString() && request.getParameter("authUsername") && request.getParameter("authPassword")) {
                authUsername = request.getParameter("authUsername")
                authPassword = request.getParameter("authPassword")
                authTenantId = request.getParameter("authTenantId")
            }
            if (authUsername) {
                this.loginUser(authUsername, authPassword, authTenantId)
            }
        }

        this.visitId = session.getAttribute("moqui.visitId")
        if (!this.visitId && !eci.getEcfi().getSkipStats()) {
            Node serverStatsNode = eci.getEcfi().getConfXmlRoot()."server-stats"[0]

            // handle visitorId and cookie
            String cookieVisitorId = null
            if (serverStatsNode."@visitor-enabled" != "false") {
                Cookie[] cookies = request.getCookies()
                if (cookies != null) {
                    for (int i = 0; i < cookies.length; i++) {
                        if (cookies[i].getName().equals("moqui.visitor")) {
                            cookieVisitorId = cookies[i].getValue()
                            break
                        }
                    }
                }
                if (cookieVisitorId) {
                    // make sure the Visitor record actually exists, if not act like we got no moqui.visitor cookie
                    EntityValue visitor = eci.entity.makeFind("moqui.server.Visitor").condition("visitorId", cookieVisitorId).disableAuthz().one()
                    if (visitor == null) {
                        logger.info("Got invalid visitorId [${cookieVisitorId}] in moqui.visitor cookie in session [${session.id}], throwing away and making a new one")
                        cookieVisitorId = null
                    }
                }
                if (!cookieVisitorId) {
                    // NOTE: disable authz for this call, don't normally want to allow create of Visitor, but this is a special case
                    Map cvResult = eci.service.sync().name("create", "moqui.server.Visitor")
                            .parameter("createdDate", getNowTimestamp()).disableAuthz().call()
                    cookieVisitorId = cvResult?.visitorId
                    logger.info("Created new Visitor with ID [${cookieVisitorId}] in session [${session.id}]")
                }
                if (cookieVisitorId) {
                    // whether it existed or not, add it again to keep it fresh; stale cookies get thrown away
                    Cookie visitorCookie = new Cookie("moqui.visitor", cookieVisitorId)
                    visitorCookie.setMaxAge(60 * 60 * 24 * 365)
                    visitorCookie.setPath("/")
                    response.addCookie(visitorCookie)
                }
            }

            if (serverStatsNode."@visit-enabled" != "false") {
                // create and persist Visit
                String contextPath = session.getServletContext().getContextPath()
                String webappId = contextPath.length() > 1 ? contextPath.substring(1) : "ROOT"
                String fullUrl = eci.web.requestUrl
                fullUrl = (fullUrl.length() > 255) ? fullUrl.substring(0, 255) : fullUrl.toString()
                Map parameters = [sessionId:session.id, webappName:webappId, fromDate:new Timestamp(session.getCreationTime()),
                        initialLocale:getLocale().toString(), initialRequest:fullUrl,
                        initialReferrer:request.getHeader("Referrer")?:"",
                        initialUserAgent:request.getHeader("User-Agent")?:"",
                        clientHostName:request.getRemoteHost(), clientUser:request.getRemoteUser()]

                try {
                    InetAddress address = InetAddress.getLocalHost()
                    if (address) {
                        parameters.serverIpAddress = address.getHostAddress()
                        parameters.serverHostName = address.getHostName()
                    }
                } catch (UnknownHostException e) {
                    logger.warn("Could not get localhost address", new BaseException("Could not get localhost address", e))
                }

                // handle proxy original address, if exists
                if (request.getHeader("X-Forwarded-For")) {
                    parameters.clientIpAddress = request.getHeader("X-Forwarded-For")
                } else {
                    parameters.clientIpAddress = request.getRemoteAddr()
                }
                if (cookieVisitorId) parameters.visitorId = cookieVisitorId

                // NOTE: disable authz for this call, don't normally want to allow create of Visit, but this is special case
                    Map visitResult = eci.service.sync().name("create", "moqui.server.Visit").parameters(parameters)
                            .disableAuthz().call()
                    // put visitId in session as "moqui.visitId"
                    if (visitResult) {
                        session.setAttribute("moqui.visitId", visitResult.visitId)
                        this.visitId = visitResult.visitId
                        logger.info("Created new Visit with ID [${this.visitId}] in session [${session.id}]")
                    }
            }
        }
    }

    @Override
    Locale getLocale() {
        if (localeCache != null) return localeCache
        Locale locale = null
        if (this.username) {
            String localeStr = getUserAccount().locale
            if (localeStr) locale = localeStr.contains("_") ?
                new Locale(localeStr.substring(0, localeStr.indexOf("_")), localeStr.substring(localeStr.indexOf("_")+1).toUpperCase()) :
                new Locale(localeStr)
        } else {
            locale = noUserLocale
        }
        return localeCache = (locale ?: (request ? request.getLocale() : Locale.getDefault()))
    }

    @Override
    void setLocale(Locale locale) {
        if (this.username) {
            boolean alreadyDisabled = eci.getArtifactExecution().disableAuthz()
            boolean beganTransaction = eci.transaction.begin(null)
            try {
                userAccount.set("locale", locale.toString())
                userAccount.update()
            } catch (Throwable t) {
                eci.transaction.rollback(beganTransaction, "Error saving timeZone", t)
            } finally {
                if (eci.transaction.isTransactionInPlace()) eci.transaction.commit(beganTransaction)
                if (!alreadyDisabled) eci.getArtifactExecution().enableAuthz()
            }
        } else {
            noUserLocale = locale
        }
        clearPerUserValues()
    }

    @Override
    TimeZone getTimeZone() {
        TimeZone tz = null
        if (this.username) {
            String tzStr = getUserAccount().timeZone
            if (tzStr) tz = TimeZone.getTimeZone(tzStr)
        } else {
            tz = noUserTimeZone
        }
        return tz ?: TimeZone.getDefault()
    }

    Calendar getCalendarSafe() {
        if (internalUserAccount != null) {
            return Calendar.getInstance(getTimeZone(), getLocale())
        } else {
            return Calendar.getInstance(noUserTimeZone ?: TimeZone.getDefault(),
                    noUserLocale ?: (request ? request.getLocale() : Locale.getDefault()))
        }
    }

    Calendar getCalendarForTzLcOnly() {
        if (calendarForTzLcOnly != null) return calendarForTzLcOnly
        return calendarForTzLcOnly = getCalendarSafe()
    }

    @Override
    void setTimeZone(TimeZone tz) {
        if (this.username) {
            boolean alreadyDisabled = eci.getArtifactExecution().disableAuthz()
            boolean beganTransaction = eci.transaction.begin(null)
            try {
                userAccount.set("timeZone", tz.getID())
                userAccount.update()
            } catch (Throwable t) {
                eci.transaction.rollback(beganTransaction, "Error saving timeZone", t)
            } finally {
                if (eci.transaction.isTransactionInPlace()) eci.transaction.commit(beganTransaction)
                if (!alreadyDisabled) eci.getArtifactExecution().enableAuthz()
            }
        } else {
            noUserTimeZone = tz
        }
        clearPerUserValues()
    }

    @Override
    String getCurrencyUomId() { return this.username ? this.userAccount.currencyUomId : noUserCurrencyUomId }

    @Override
    void setCurrencyUomId(String uomId) {
        if (this.username) {
            boolean alreadyDisabled = eci.getArtifactExecution().disableAuthz()
            boolean beganTransaction = eci.transaction.begin(null)
            try {
                userAccount.set("currencyUomId", uomId)
                userAccount.update()
            } catch (Throwable t) {
                eci.transaction.rollback(beganTransaction, "Error saving currencyUomId", t)
            } finally {
                if (eci.transaction.isTransactionInPlace()) eci.transaction.commit(beganTransaction)
                if (!alreadyDisabled) eci.getArtifactExecution().enableAuthz()
            }
        } else {
            noUserCurrencyUomId = uomId
        }
    }

    @Override
    String getPreference(String preferenceKey) {
        String userId = getUserId()
        if (!userId) return null
        return getPreference(preferenceKey, userId)
    }

    String getPreference(String preferenceKey, String userId) {
        EntityValue up = eci.getEntity().makeFind("moqui.security.UserPreference").condition("userId", userId)
                .condition("preferenceKey", preferenceKey).useCache(true).disableAuthz().one()
        if (up == null) {
            // try UserGroupPreference
            EntityList ugpList = eci.getEntity().makeFind("moqui.security.UserGroupPreference")
                    .condition("userGroupId", EntityCondition.IN, getUserGroupIdSet(userId))
                    .condition("preferenceKey", preferenceKey).useCache(true).disableAuthz().list()
            if (ugpList) up = ugpList.first
        }
        return up?.preferenceValue
    }

    @Override
    void setPreference(String preferenceKey, String preferenceValue) {
        String userId = getUserId()
        if (!userId) throw new IllegalStateException("Cannot set preference with key [${preferenceKey}], no user logged in.")
        boolean alreadyDisabled = eci.getArtifactExecution().disableAuthz()
        boolean beganTransaction = eci.transaction.begin(null)
        try {
            eci.getEntity().makeValue("moqui.security.UserPreference").set("userId", getUserId())
                    .set("preferenceKey", preferenceKey).set("preferenceValue", preferenceValue).createOrUpdate()
        } catch (Throwable t) {
            eci.transaction.rollback(beganTransaction, "Error saving UserPreference", t)
        } finally {
            if (eci.transaction.isTransactionInPlace()) eci.transaction.commit(beganTransaction)
            if (!alreadyDisabled) eci.getArtifactExecution().enableAuthz()
        }
    }

    @Override
    Timestamp getNowTimestamp() {
        // NOTE: review Timestamp and nowTimestamp use, have things use this by default (except audit/etc where actual date/time is needed
        return this.effectiveTime ? this.effectiveTime : new Timestamp(System.currentTimeMillis())
    }

    @Override
    void setEffectiveTime(Timestamp effectiveTime) { this.effectiveTime = effectiveTime }

    @Override
    boolean loginUser(String username, String password, String tenantId) {
        if (!username) {
            eci.message.addError("No username specified")
            return false
        }
        if (tenantId) {
            eci.changeTenant(tenantId)
            this.visitId = null
            if (eci.web != null) eci.web.session.removeAttribute("moqui.visitId")
        }

        UsernamePasswordToken token = new UsernamePasswordToken(username, password)
        token.rememberMe = true
        if (currentUser == null) {
            // no currentUser, this usually means we are running outside of a web/servlet context
            currentUser = eci.getEcfi().getSecurityManager().createSubject(new DefaultSubjectContext())
        }
        try {
            currentUser.login(token)

            // do this first so that the rest will be done as this user
            // just in case there is already a user authenticated push onto a stack to remember
            usernameStack.addFirst(username)
            internalUserAccount = null
            internalUserGroupIdSet = null
            internalArtifactTarpitCheckList = null
            internalArtifactAuthzCheckList = null

            // after successful login trigger the after-login actions
            if (eci.web != null) eci.web.runAfterLoginActions()
        } catch (AuthenticationException ae) {
            // others to consider handling differently (these all inherit from AuthenticationException):
            //     UnknownAccountException, IncorrectCredentialsException, ExpiredCredentialsException,
            //     CredentialsException, LockedAccountException, DisabledAccountException, ExcessiveAttemptsException
            eci.message.addError(ae.message)
            logger.warn("Login failure: ${eci.message.errorsString}", ae)
            return false
        }

        clearPerUserValues()
        return true
    }

    void logoutUser() {
        // before logout trigger the before-logout actions
        if (eci.web != null) eci.web.runBeforeLogoutActions()

        if (usernameStack) {
            usernameStack.removeFirst()
            internalUserAccount = null
            internalUserGroupIdSet = null
            internalArtifactTarpitCheckList = null
            internalArtifactAuthzCheckList = null
        }

        if (eci.web != null) {
            eci.web.session.removeAttribute("moqui.tenantId")
            eci.web.session.removeAttribute("moqui.visitId")
        }
        currentUser.logout()
        clearPerUserValues()
    }

    boolean loginAnonymousIfNoUser() {
        if (usernameStack.size() == 0 && !loggedInAnonymous) {
            loggedInAnonymous = true
            return true
        } else {
            return false
        }
    }
    void logoutAnonymousOnly() { loggedInAnonymous = false }
    boolean getLoggedInAnonymous() { return loggedInAnonymous }

    @Override
    boolean hasPermission(String userPermissionId) { return hasPermission(getUserId(), userPermissionId, getNowTimestamp(), eci) }

    static boolean hasPermission(String username, String userPermissionId, Timestamp whenTimestamp, ExecutionContextImpl eci) {
        if (whenTimestamp == null) whenTimestamp = new Timestamp(System.currentTimeMillis())
        boolean alreadyDisabled = eci.getArtifactExecution().disableAuthz()
        try {
            EntityValue ua = eci.getEntity().makeFind("moqui.security.UserAccount").condition("userId", username).useCache(true).one()
            if (ua == null) ua = eci.getEntity().makeFind("moqui.security.UserAccount").condition("username", username).useCache(true).one()
            if (ua == null) return false
            return (eci.getEntity().makeFind("moqui.security.UserPermissionCheck")
                    .condition([userId:ua.userId, userPermissionId:userPermissionId]).useCache(true).list()
                    .filterByDate("groupFromDate", "groupThruDate", whenTimestamp)
                    .filterByDate("permissionFromDate", "permissionThruDate", whenTimestamp)) as boolean
        } finally {
            if (!alreadyDisabled) eci.getArtifactExecution().enableAuthz()
        }
    }

    @Override
    boolean isInGroup(String userGroupId) { return isInGroup(getUserId(), userGroupId, getNowTimestamp(), eci) }

    static boolean isInGroup(String username, String userGroupId, Timestamp whenTimestamp, ExecutionContextImpl eci) {
        if (userGroupId == "ALL_USERS") return true
        if (whenTimestamp == null) whenTimestamp = new Timestamp(System.currentTimeMillis())
        boolean alreadyDisabled = eci.getArtifactExecution().disableAuthz()
        try {
            EntityValue ua = eci.getEntity().makeFind("moqui.security.UserAccount").condition("userId", username).useCache(true).one()
            if (ua == null) ua = eci.getEntity().makeFind("moqui.security.UserAccount").condition("username", username).useCache(true).one()
            if (ua == null) return false
            return (eci.getEntity().makeFind("moqui.security.UserGroupMember").condition([userId:ua.userId, userGroupId:userGroupId])
                    .useCache(true).list().filterByDate("fromDate", "thruDate", whenTimestamp)) as boolean
        } finally {
            if (!alreadyDisabled) eci.getArtifactExecution().enableAuthz()
        }
    }

    @Override
    Set<String> getUserGroupIdSet() {
        // first get the groups the user is in (cached), always add the "ALL_USERS" group to it
        if (usernameStack.size() == 0) return allUserGroupIdOnly
        if (internalUserGroupIdSet == null) internalUserGroupIdSet = getUserGroupIdSet(getUserId())
        return internalUserGroupIdSet
    }

    Set<String> getUserGroupIdSet(String userId) {
        Set<String> groupIdSet = new HashSet(allUserGroupIdOnly)
        if (userId) {
            // expand the userGroupId Set with UserGroupMember
            EntityList ugmList = eci.getEntity().makeFind("moqui.security.UserGroupMember").condition("userId", userId)
                    .useCache(true).disableAuthz().list().filterByDate(null, null, null)
            for (EntityValue userGroupMember in ugmList) groupIdSet.add((String) userGroupMember.userGroupId)
        }

        return groupIdSet
    }

    EntityList getArtifactTarpitCheckList() {
        if (usernameStack.size() == 0) return EntityListImpl.EMPTY
        if (internalArtifactTarpitCheckList == null) {
            // get the list for each group separately to increase cache hits/efficiency
            internalArtifactTarpitCheckList = new EntityListImpl(eci.getEcfi().getEntityFacade())
            for (String userGroupId in getUserGroupIdSet()) {
                internalArtifactTarpitCheckList.addAll(eci.getEntity().makeFind("moqui.security.ArtifactTarpitCheckView")
                        .condition("userGroupId", userGroupId).useCache(true).list())
            }
        }
        return internalArtifactTarpitCheckList
    }

    EntityList getArtifactAuthzCheckList() {
        // NOTE: even if there is no user, still consider part of the ALL_USERS group and such: if (usernameStack.size() == 0) return EntityListImpl.EMPTY
        if (internalArtifactAuthzCheckList == null) {
            // get the list for each group separately to increase cache hits/efficiency
            internalArtifactAuthzCheckList = new EntityListImpl(eci.getEcfi().getEntityFacade())
            for (String userGroupId in getUserGroupIdSet()) {
                internalArtifactAuthzCheckList.addAll(eci.getEntity().makeFind("moqui.security.ArtifactAuthzCheckView")
                        .condition("userGroupId", userGroupId).useCache(true).list())
            }
        }
        return internalArtifactAuthzCheckList
    }

    @Override
    String getUserId() { return getUserAccount()?.userId }

    @Override
    String getUsername() { return this.usernameStack.size() > 0 ? this.usernameStack.peekFirst() : null }

    @Override
    EntityValue getUserAccount() {
        if (this.usernameStack.size() == 0) return null
        if (internalUserAccount == null) {
            internalUserAccount = eci.getEntity().makeFind("moqui.security.UserAccount")
                    .condition("username", this.getUsername()).useCache(false).disableAuthz().one()
            // this is necessary as temporary values may have been set before the UserAccount was retrieved
            clearPerUserValues()
        }
        // logger.info("Got UserAccount [${internalUserAccount}] with userIdStack [${userIdStack}]")
        return internalUserAccount
    }

    @Override
    String getVisitUserId() { return visitId ? getVisit().userId : null }

    @Override
    String getVisitId() { return visitId }

    @Override
    EntityValue getVisit() {
        if (!visitId) return null
        EntityValue vst = eci.getEntity().makeFind("moqui.server.Visit").condition("visitId", visitId).useCache(true).disableAuthz().one()
        return vst
    }

    @Override
    List<NotificationMessage> getNotificationMessages(String topic) {
        if (!getUserId()) return []
        return eci.getEcfi().getNotificationMessages(getUserId(), topic)
    }
}
