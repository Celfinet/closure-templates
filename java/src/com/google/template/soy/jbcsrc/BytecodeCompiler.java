/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.jbcsrc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.template.soy.jbcsrc.api.CompiledTemplate;
import com.google.template.soy.soytree.TemplateBasicNode;
import com.google.template.soy.soytree.TemplateRegistry;

import java.util.Map;

/**
 * The entry point to the {@code jbcsrc} compiler.
 */
final class BytecodeCompiler {
  /**
   * Compiles all the templates in the given registry.
   *
   * <p>TODO(lukes): this interface is insufficient.  We will eventually need additional data to
   * implement print directives, escaping directives, and soy functions.  Look at the jssrc compiler
   * to see how it is configured.
   */
  static CompiledTemplates compile(TemplateRegistry registry) {
    CompiledTemplateRegistry compilerRegistry = new CompiledTemplateRegistry(registry);

    // TODO(lukes): currently we compile all the classes, but you could easily imagine being
    // configured in such a way that we load the classes from the system class loader.  Then we
    // could add a build phase that writes the compiled templates out to a jar.  Then in the non
    // development mode case we could skip even parsing templates!
    MemoryClassLoader loader = compileTemplates(registry, compilerRegistry);
    ImmutableMap.Builder<String, CompiledTemplate.Factory> factories = ImmutableMap.builder();
    // TODO(lukes): support deltemplates eventually
    for (String name : registry.getBasicTemplatesMap().keySet()) {
      factories.put(name, loadFactory(compilerRegistry.getTemplateInfo(name), loader));
    }
    return new CompiledTemplates(factories.build());
  }

  @VisibleForTesting static CompiledTemplate.Factory loadFactory(
      CompiledTemplateMetadata templateInfo,
      ClassLoader loader) {
    // We construct the factories via reflection to bridge the gap between generated and
    // non-generated code.  However, each factory only needs to be constructed once so the
    // reflective cost isn't paid on a per render basis.
    CompiledTemplate.Factory factory;
    try {
      Class<? extends CompiledTemplate.Factory> factoryClass =
          Class.forName(templateInfo.factory().className(), true /* run clinit */, loader)
              .asSubclass(CompiledTemplate.Factory.class);
      factory = factoryClass.newInstance();
    } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
      // this should be impossible since our factories are public with a default constructor.
      // TODO(lukes): failures of bytecode verification will propagate as Errors, we should
      // consider catching them here to add information about our generated types. (e.g. add the
      // class trace and a pointer on how to file a soy bug)
      throw new AssertionError(e);
    }
    return factory;
  }

  /**
   * Run the compiler for all templates and return the generated class in a
   * {@link MemoryClassLoader}
   */
  private static MemoryClassLoader compileTemplates(
      TemplateRegistry registry, CompiledTemplateRegistry compilerRegistry) {
    MemoryClassLoader.Builder builder = new MemoryClassLoader.Builder();
    // We generate all the classes and then start loading them.  This 2 phase process ensures that
    // we don't have to worry about ordering (where a class we have generated references a class we
    // haven't generated yet), because none of the classes are loadable until they all are.
    for (Map.Entry<String, TemplateBasicNode> template :
        registry.getBasicTemplatesMap().entrySet()) {
      String name = template.getKey();
      CompiledTemplateMetadata classInfo = compilerRegistry.getTemplateInfo(name);
      for (ClassData clazz : new TemplateCompiler(classInfo).compile()) {
        builder.add(clazz);
      }
    }
    return builder.build();
  }

  private BytecodeCompiler() {}
}
