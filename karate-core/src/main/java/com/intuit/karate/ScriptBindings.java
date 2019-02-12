/*
 * The MIT License
 *
 * Copyright 2017 Intuit Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.intuit.karate;

import com.intuit.karate.core.ScenarioContext;
import com.intuit.karate.core.ScriptBridge;
import com.intuit.karate.exception.KarateAbortException;
import com.intuit.karate.exception.KarateFileNotFoundException;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 *
 * @author pthomas3
 */
public class ScriptBindings {

    protected final ScenarioContext context;
    protected final ScriptBridge bridge;
    protected final Value bindings;

    private final Map<String, Object> adds;

    public static final String KARATE = "karate";
    public static final String KARATE_ENV = "karate.env";
    public static final String KARATE_CONFIG_DIR = "karate.config.dir";
    private static final String KARATE_DASH_CONFIG = "karate-config";
    private static final String KARATE_DASH_BASE = "karate-base";
    private static final String DOT_JS = ".js";
    public static final String KARATE_CONFIG_JS = KARATE_DASH_CONFIG + DOT_JS;
    private static final String KARATE_BASE_JS = KARATE_DASH_BASE + DOT_JS;
    public static final String READ = "read";
    public static final String DRIVER = "driver";
    public static final String DRIVER_DOT = DRIVER + ".";

    // netty / test-doubles
    public static final String PATH_MATCHES = "pathMatches";
    public static final String METHOD_IS = "methodIs";
    public static final String TYPE_CONTAINS = "typeContains";
    public static final String ACCEPT_CONTAINS = "acceptContains";
    public static final String PARAM_VALUE = "paramValue";
    public static final String PATH_PARAMS = "pathParams";
    public static final String BODY_PATH = "bodyPath";
    public static final String SERVER_PORT = "serverPort";

    private static final String READ_FUNCTION = String.format("function(path){ return %s.%s(path) }", KARATE, READ);

    public ScriptBindings(ScenarioContext context) {
        this.context = context;
        this.adds = new HashMap(8); // read, karate, self, root, parent, nashorn.global, driver, responseBytes
        bridge = new ScriptBridge(context);
        adds.put(KARATE, bridge);
        bindings = context.jsContext.getBindings("js");
        bindings.putMember(KARATE, bridge);
        // the next line calls an eval with 'incomplete' bindings
        // i.e. only the 'karate' bridge has been bound so far
        ScriptValue readFunction = eval(READ_FUNCTION, context.jsContext);
        // and only now are the bindings complete - with the 'read' function
        adds.put(READ, readFunction.getAsJsValue());
    }

    private static final String READ_INVOKE = "%s('%s%s')";
    private static final String READ_KARATE_CONFIG_DEFAULT = String.format(READ_INVOKE, READ, FileUtils.CLASSPATH_COLON, KARATE_CONFIG_JS);
    public static final String READ_KARATE_CONFIG_BASE = String.format(READ_INVOKE, READ, FileUtils.CLASSPATH_COLON, KARATE_BASE_JS);

    public static final String readKarateConfigForEnv(boolean isForDefault, String configDir, String env) {
        if (isForDefault) {
            if (configDir == null) {
                return READ_KARATE_CONFIG_DEFAULT; // only look for classpath:karate-config.js
            } else { // if the user set a config dir, look for karate-config.js but as a file in that dir
                File configFile = new File(configDir + "/" + KARATE_CONFIG_JS);
                if (configFile.exists()) {
                    return String.format(READ_INVOKE, READ, FileUtils.FILE_COLON, configFile.getPath().replace('\\', '/'));
                } else { // if karate-config.js was not over-ridden
                    // user intent is likely to over-ride env config, see 'else' block for this function
                    return READ_KARATE_CONFIG_DEFAULT; // default to classpath:karate-config.js
                }
            }
        } else {
            if (configDir == null) { // look for classpath:karate-config-<env>.js
                return String.format(READ_INVOKE, READ, FileUtils.CLASSPATH_COLON, KARATE_DASH_CONFIG + "-" + env + DOT_JS);
            } else { // look for file:<karate.config.dir>/karate-config-<env>.js
                File configFile = new File(configDir + "/" + KARATE_DASH_CONFIG + "-" + env + DOT_JS);
                return String.format(READ_INVOKE, READ, FileUtils.FILE_COLON, configFile.getPath().replace('\\', '/'));
            }
        }
    }

    public ScriptValue evalWithContext(String exp, ScriptEvalContext ec) { // TODO optimize
        Context jsContext = context.jsContext;        
        if (ec == null) {
            adds.remove(Script.VAR_SELF);
            adds.remove(Script.VAR_ROOT);
            adds.remove(Script.VAR_PARENT);
        } else {
            // ec.selfValue will never be null
            adds.put(Script.VAR_SELF, ec.selfValue.getAsJsValue());
            adds.put(Script.VAR_ROOT, new ScriptValue(ec.root).getAsJsValue());
            adds.put(Script.VAR_PARENT, new ScriptValue(ec.parent).getAsJsValue());
        }
        context.vars.forEach((k, v) -> {
            bindings.putMember(k, v.getAsJsValue());
        });
        adds.forEach((k, v) -> {
            bindings.putMember(k, JsUtils.toJsValue(v));
        });
        for (String key : bindings.getMemberKeys()) {
            if (!context.vars.containsKey(key) && !adds.containsKey(key)) {
                try {
                    bindings.removeMember(key);
                } catch (Exception e) {
                    // a variable was defined in JS that we can't erase
                }
            }
        }
        return eval(exp, jsContext);
    }

    public static ScriptValue eval(String exp, Context context) {
        if (StringUtils.isJavaScriptFunction(exp)) {
            exp = "(" + exp + ")";
        }
        try {
            Value value = context.eval("js", exp);
            Object jsValue = JsUtils.fromJsValue(value, context);
            return new ScriptValue(jsValue);
        } catch (Exception e) {
            String message = e.getMessage();
            // reduce log bloat for common file-not-found situation / handle karate.abort()
            if (message.contains("[karate:abort]")) {
                throw new KarateAbortException(null);
            } else if (message.contains("[karate:file]")) {
                throw new KarateFileNotFoundException(message);
            } else {
                throw new RuntimeException("javascript evaluation failed: " + exp + ", " + e.getMessage(), e);
            }
        }
    }

    public void putAdditionalVariable(String name, Object value) {
        adds.put(name, value);
    }

}
