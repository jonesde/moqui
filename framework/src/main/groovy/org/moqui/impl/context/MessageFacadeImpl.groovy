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

import groovy.transform.CompileStatic
import org.moqui.context.MessageFacade
import org.moqui.context.ValidationError

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
public class MessageFacadeImpl implements MessageFacade {
    protected final static Logger logger = LoggerFactory.getLogger(MessageFacadeImpl.class)

    protected List<String> messageList = new LinkedList<String>()
    protected List<String> errorList = new LinkedList<String>()
    protected List<ValidationError> validationErrorList = new LinkedList<ValidationError>()

    MessageFacadeImpl() { }

    @Override
    List<String> getMessages() { return this.messageList }
    String getMessagesString() {
        StringBuilder messageBuilder = new StringBuilder()
        for (String message in messageList) messageBuilder.append(message).append("\n")
        return messageBuilder.toString()
    }
    void addMessage(String message) { if (message) this.messageList.add(message) }

    @Override
    List<String> getErrors() { return this.errorList }
    void addError(String error) { if (error) this.errorList.add(error) }

    @Override
    List<ValidationError> getValidationErrors() { return this.validationErrorList }
    void addValidationError(String form, String field, String serviceName, String message, Throwable nested) {
        this.validationErrorList.add(new ValidationError(form, field, serviceName, message, nested))
    }

    boolean hasError() { return errorList.size() > 0 || validationErrorList.size() > 0 }
    String getErrorsString() {
        StringBuilder errorBuilder = new StringBuilder()
        for (String errorMessage in errorList) errorBuilder.append(errorMessage).append("\n")
        for (ValidationError validationError in validationErrorList) errorBuilder.append("${validationError.message} (for field ${validationError.field}${validationError.form ? ' on form ' + validationError.form : ''}${validationError.serviceName ? ' of service ' + validationError.serviceName : ''})").append("\n")
        return errorBuilder.toString()
    }

    void clearErrors() { errorList.clear(); validationErrorList.clear(); }
}
