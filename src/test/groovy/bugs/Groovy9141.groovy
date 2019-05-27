/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package groovy.bugs

import gls.CompilableTestSupport
import groovy.transform.CompileStatic
import org.codehaus.groovy.control.CompilerConfiguration

import static org.codehaus.groovy.control.ParserVersion.V_2

@CompileStatic
final class Groovy9141 extends CompilableTestSupport {
    private static final String ABSTRACT_METHOD_WITH_BODY = '''
        abstract meth() { }
    '''

    // not a language requirement but script-level check takes precedence in current implementation
    void testAbstractMethodWithBodyInScript() {
        def err = shouldNotCompile ABSTRACT_METHOD_WITH_BODY
        assert err =~ / You cannot define an abstract method\[meth] in the script. Try removing the 'abstract' /
    }

    void testAbstractMethodWithBodyInClass() {
        def err = shouldNotCompile """
            class Main {
                $ABSTRACT_METHOD_WITH_BODY
            }
        """
        // not a language requirement but class level check takes precedence in current implementation
        assert err =~ / Can't have an abstract method in a non-abstract class. /
    }

    void testAbstractMethodWithBodyInCAbstractlass() {
        def err = shouldNotCompile """
            abstract class Main {
                $ABSTRACT_METHOD_WITH_BODY
            }
        """
        assert err =~ / You defined an abstract method\[meth] with a body. Try removing the method body @ line /
    }

    void testAbstractMethodWithBodyInScript_oldParser() {
        def cc = new CompilerConfiguration(parserVersion: V_2)
        def err = shouldFail {
            new GroovyShell(cc).evaluate ABSTRACT_METHOD_WITH_BODY
        }
        assert err =~ / Abstract methods do not define a body/
    }

}
