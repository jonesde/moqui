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
package org.moqui.impl.service

import org.moqui.impl.actions.XmlAction
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.context.ExecutionContext
import javax.transaction.xa.XAException
import javax.transaction.Transaction
import javax.transaction.Status
import javax.transaction.TransactionManager
import javax.transaction.xa.Xid
import javax.transaction.xa.XAResource

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ServiceEcaRule {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceEcaRule.class)

    protected Node secaNode
    protected String location

    protected XmlAction condition = null
    protected XmlAction actions = null

    ServiceEcaRule(ExecutionContextFactoryImpl ecfi, Node secaNode, String location) {
        this.secaNode = secaNode
        this.location = location

        // prep condition
        if (secaNode.condition && secaNode.condition[0].children()) {
            // the script is effectively the first child of the condition element
            condition = new XmlAction(ecfi, (Node) secaNode.condition[0].children()[0], location + ".condition")
        }
        // prep actions
        if (secaNode.actions) {
            actions = new XmlAction(ecfi, (Node) secaNode.actions[0], location + ".actions")
        }
    }

    String getServiceName() { return secaNode."@service" }
    String getWhen() { return secaNode."@when" }
    // Node getSecaNode() { return secaNode }

    void runIfMatches(String serviceName, Map<String, Object> parameters, Map<String, Object> results, String when, ExecutionContext ec) {
        // see if we match this event and should run
        if (serviceName != secaNode."@service") return
        if (when != secaNode."@when") return
        if (ec.getMessage().hasError() && secaNode."@run-on-error" != "true") return

        standaloneRun(parameters, results, ec)
    }

    void standaloneRun(Map<String, Object> parameters, Map<String, Object> results, ExecutionContext ec) {
        try {
            ec.context.push()
            ec.context.putAll(parameters)
            ec.context.put("parameters", parameters)
            if (results != null) {
                ec.context.putAll(results)
                ec.context.put("results", results)
            }

            // run the condition and if passes run the actions
            boolean conditionPassed = true
            if (condition) conditionPassed = condition.checkCondition(ec)
            if (conditionPassed) {
                if (actions) actions.run(ec)
            }
        } finally {
            ec.context.pop()
        }
    }

    void registerTx(String serviceName, Map<String, Object> parameters, ExecutionContextFactoryImpl ecfi) {
        if (serviceName != secaNode."@service") return
        def sxr = new SecaXaResource(this, parameters, ecfi)
        sxr.enlist()
    }

    static class SecaXaResource implements XAResource {
        protected final static Logger logger = LoggerFactory.getLogger(SecaXaResource.class)

        protected ExecutionContextFactoryImpl ecfi
        protected ServiceEcaRule sec
        protected Map<String, Object> parameters

        protected Transaction tx = null
        protected Xid xid = null
        protected Integer timeout = null

        protected boolean active = false
        protected boolean suspended = false

        SecaXaResource(ServiceEcaRule sec, Map<String, Object> parameters, ExecutionContextFactoryImpl ecfi) {
            this.ecfi = ecfi
            this.sec = sec
            this.parameters = new HashMap(parameters)
        }

        void enlist() {
            TransactionManager tm = ecfi.transactionFacade.getTransactionManager()
            if (tm == null || tm.getStatus() != Status.STATUS_ACTIVE) throw new XAException("Cannot enlist: no transaction manager or transaction not active")

            Transaction tx = tm.getTransaction();
            if (tx == null) throw new XAException(XAException.XAER_NOTA)

            this.tx = tx
            tx.enlistResource(this)
        }

        @Override
        void start(Xid xid, int flag) throws XAException {
            if (this.active) {
                if (this.xid != null && this.xid.equals(xid)) {
                    throw new XAException(XAException.XAER_DUPID);
                } else {
                    throw new XAException(XAException.XAER_PROTO);
                }
            }
            if (this.xid != null && !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)

            this.active = true
            this.suspended = false
            this.xid = xid
        }

        @Override
        void end(Xid xid, int flag) throws XAException {
            if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)
            if (flag == TMSUSPEND) {
                if (!this.active) throw new XAException(XAException.XAER_PROTO)
                this.suspended = true
            }
            if (flag == TMSUCCESS || flag == TMFAIL) {
                // allow a success/fail end if TX is suspended without a resume flagged start first
                if (!this.active && !this.suspended) throw new XAException(XAException.XAER_PROTO)
            }
            this.active = false
        }

        @Override
        void forget(Xid xid) throws XAException {
            if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)
            this.xid = null
            if (active) logger.warn("forget() called without end()")
        }

        @Override
        int prepare(Xid xid) throws XAException {
            if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)
            return XA_OK
        }

        @Override
        Xid[] recover(int flag) throws XAException { return this.xid != null ? [this.xid] : [] }
        @Override
        boolean isSameRM(XAResource xaResource) throws XAException { return xaResource == this }
        @Override
        int getTransactionTimeout() throws XAException { return this.timeout == null ? 0 : this.timeout }
        @Override
        boolean setTransactionTimeout(int seconds) throws XAException {
            this.timeout = (seconds == 0 ? null : seconds)
            return true
        }

        @Override
        void commit(Xid xid, boolean onePhase) throws XAException {
            if (this.active) logger.warn("commit() called without end()")
            if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)

            // run in separate thread and tx
            if (sec.secaNode."@when" == "tx-commit") runInThreadAndTx()

            this.xid = null
            this.active = false
        }

        @Override
        void rollback(Xid xid) throws XAException {
            if (this.active) logger.warn("rollback() called without end()")
            if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)

            // run in separate thread and tx
            if (sec.secaNode."@when" == "tx-rollback") runInThreadAndTx()

            this.xid = null
            this.active = false
        }

        void runInThreadAndTx() {
            Thread thread = new Thread() {
                @Override
                public void run() {
                    boolean beganTransaction = ecfi.transactionFacade.begin(null)
                    try {
                        sec.standaloneRun(parameters, null, ecfi.executionContext)
                    } catch (Throwable t) {
                        logger.error("Error running Service TX ECA rule", t)
                        ecfi.transactionFacade.rollback(beganTransaction, "Error running Service TX ECA rule", t)
                    } finally {
                        if (beganTransaction && ecfi.transactionFacade.isTransactionInPlace())
                            ecfi.transactionFacade.commit()
                    }
                }
            };
            thread.start();
        }
    }
}
