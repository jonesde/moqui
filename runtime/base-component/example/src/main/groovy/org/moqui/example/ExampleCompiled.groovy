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
package org.moqui.example

class ExampleCompiled {
    protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ExampleCompiled.class)

    static void testMethod() { logger.warn("The testMethod was called!") }
    static String echo(String input) { input }
}
