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

package com.google.template.soy.pysrc.internal;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.pysrc.internal.GenPyExprsVisitor.GenPyExprsVisitorFactory;
import com.google.template.soy.pysrc.internal.TranslateToPyExprVisitor.TranslateToPyExprVisitorFactory;
import com.google.template.soy.pysrc.restricted.PyExpr;
import com.google.template.soy.pysrc.restricted.PyExprUtils;
import com.google.template.soy.pysrc.restricted.PyFunctionExprBuilder;
import com.google.template.soy.pysrc.restricted.PyListExpr;
import com.google.template.soy.pysrc.restricted.PyStringExpr;
import com.google.template.soy.pysrc.restricted.SoyPySrcPrintDirective;
import com.google.template.soy.soytree.AbstractReturningSoyNodeVisitor;
import com.google.template.soy.soytree.CallBasicNode;
import com.google.template.soy.soytree.CallDelegateNode;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.CallParamContentNode;
import com.google.template.soy.soytree.CallParamNode;
import com.google.template.soy.soytree.CallParamValueNode;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.inject.Inject;

/**
 * Functions for generating Python code for template calls and their parameters.
 *
 */
final class GenPyCallExprVisitor extends AbstractReturningSoyNodeVisitor<PyExpr>{

  private final ImmutableMap<String, SoyPySrcPrintDirective> soyPySrcDirectivesMap;

  private final IsComputableAsPyExprVisitor isComputableAsPyExprVisitor;

  private final IsCalleeInFileVisitor isCalleeInFileVisitor;

  private final GenPyExprsVisitorFactory genPyExprsVisitorFactory;

  private final TranslateToPyExprVisitorFactory translateToPyExprVisitorFactory;

  private LocalVariableStack localVarStack;


  @Inject
  GenPyCallExprVisitor(
      ImmutableMap<String, SoyPySrcPrintDirective> soyPySrcDirectivesMap,
      IsComputableAsPyExprVisitor isComputableAsPyExprVisitor,
      IsCalleeInFileVisitor isCalleeInFileVisitor,
      GenPyExprsVisitorFactory genPyExprsVisitorFactory,
      TranslateToPyExprVisitorFactory translateToPyExprVisitorFactory) {
    this.soyPySrcDirectivesMap = soyPySrcDirectivesMap;
    this.isComputableAsPyExprVisitor = isComputableAsPyExprVisitor;
    this.isCalleeInFileVisitor = isCalleeInFileVisitor;
    this.genPyExprsVisitorFactory = genPyExprsVisitorFactory;
    this.translateToPyExprVisitorFactory = translateToPyExprVisitorFactory;
  }

  /**
   * Generates the Python expression for a given call.
   *
   * <p>Important: If there are CallParamContentNode children whose contents are not computable as
   * Python expressions, then this function assumes that, elsewhere, code has been generated to
   * define their respective {@code param<n>} temporary variables.
   *
   * <p>Here are five example calls:
   * <pre>
   *   {call some.func data="all" /}
   *   {call some.func data="$boo" /}
   *   {call some.func}
   *     {param goo = $moo /}
   *   {/call}
   *   {call some.func data="$boo"}
   *     {param goo}Blah{/param}
   *   {/call}
   *   {call some.func}
   *     {param goo}
   *       {for $i in range(3)}{$i}{/for}
   *     {/param}
   *   {/call}
   * </pre>
   * Their respective generated calls might be the following:
   * <pre>
   *   some.func(opt_data)
   *   some.func(opt_data.get('boo'))
   *   some.func({'goo': opt_data.get('moo')})
   *   some.func(runtime.merge_into_dict({'goo': 'Blah'}, opt_data.get('boo')))
   *   some.func({'goo': param65})
   * </pre>
   * Note that in the last case, the param content is not computable as Python expressions, so we
   * assume that code has been generated to define the temporary variable {@code param<n>}.
   *
   * @param callNode The call to generate code for.
   * @param localVarStack The current stack of replacement Python expressions for the local
   *     variables (and foreach-loop special functions) current in scope.
   * @return The Python expression for the call.
   */
  PyExpr exec(CallNode callNode, LocalVariableStack localVarStack) {
    this.localVarStack = localVarStack;
    PyExpr callExpr = visit(callNode);
    this.localVarStack = null;
    return callExpr;
  }

  /**
   * Visits basic call nodes and builds the call expression. If the callee is in the file, it can be
   * accessed directly, but if it's in another file, the module name must be prefixed.
   *
   * @param node The basic call node.
   * @return The call Python expression.
   */
  @Override protected PyExpr visitCallBasicNode(CallBasicNode node) {
    String calleeName = node.getCalleeName();

    // Build the Python expr text for the callee.
    String calleeExprText;
    if (isCalleeInFileVisitor.visitCallBasicNode(node)) {
      // If in the same module no namespace is required.
      calleeExprText = calleeName.substring(calleeName.lastIndexOf('.') + 1);
    } else {
      // If in another module, the module name is required along with the function name.
      int secondToLastDotIndex = calleeName.lastIndexOf('.', calleeName.lastIndexOf('.') - 1);
      calleeExprText = calleeName.substring(secondToLastDotIndex + 1);
    }

    String callExprText = calleeExprText + "(" + genObjToPass(node) + ", opt_ijData)";
    return escapeCall(callExprText, node.getEscapingDirectiveNames());
  }

  /**
   * Visits a delegate call node and builds the call expression to retrieve the function and execute
   * it. The get_delegate_fn returns the function directly, so its output can be called directly.
   *
   * @param node The delegate call node.
   * @return The call Python expression.
   */
  @Override protected PyExpr visitCallDelegateNode(CallDelegateNode node) {
    ExprRootNode variantSoyExpr = node.getDelCalleeVariantExpr();
    PyExpr variantPyExpr;
    if (variantSoyExpr == null) {
      // Case 1: Delegate call with empty variant.
      variantPyExpr = new PyStringExpr("''");
    } else {
      // Case 2: Delegate call with variant expression.
      TranslateToPyExprVisitor translator = translateToPyExprVisitorFactory.create(localVarStack);
      variantPyExpr = translator.exec(variantSoyExpr);
    }
    String calleeExprText = new PyFunctionExprBuilder("runtime.get_delegate_fn")
        .addArg(node.getDelCalleeName())
        .addArg(variantPyExpr)
        .addArg(node.allowsEmptyDefault())
        .build();

    String callExprText = calleeExprText + "(" + genObjToPass(node) + ", opt_ijData)";
    return escapeCall(callExprText, node.getEscapingDirectiveNames());
  }

  /**
   * Generates the Python expression for the object to pass in a given call. This expression will be
   * a combination of passed data and additional content params. If both are passed, they'll be
   * combined into one dictionary.
   *
   * @param callNode The call to generate code for.
   * @return The Python expression for the object to pass in the call.
   */
  public String genObjToPass(CallNode callNode) {
    TranslateToPyExprVisitor translator = translateToPyExprVisitorFactory.create(localVarStack);

    // Generate the expression for the original data to pass.
    String dataToPass;
    if (callNode.isPassingAllData()) {
      dataToPass = "opt_data";
    } else if (callNode.isPassingData()) {
      dataToPass = translator.exec(callNode.getDataExpr()).getText();
    } else {
      dataToPass = "None";
    }

    // Case 1: No additional params.
    if (callNode.numChildren() == 0) {
      return dataToPass;
    }

    // Build an object literal containing the additional params.
    Map<PyExpr, PyExpr> additionalParams = new LinkedHashMap<>();

    for (CallParamNode child : callNode.getChildren()) {
      PyExpr key = new PyStringExpr("'" + child.getKey() + "'");

      if (child instanceof CallParamValueNode) {
        CallParamValueNode cpvn = (CallParamValueNode) child;
        additionalParams.put(key, translator.exec(cpvn.getValueExprUnion().getExpr()));
      } else {
        CallParamContentNode cpcn = (CallParamContentNode) child;
        PyExpr valuePyExpr;
        if (isComputableAsPyExprVisitor.exec(cpcn)) {
          valuePyExpr = PyExprUtils.concatPyExprs(
              genPyExprsVisitorFactory.create(localVarStack).exec(cpcn));
        } else {
          // This is a param with content that cannot be represented as Python expressions, so we
          // assume that code has been generated to define the temporary variable 'param<n>'.
          String paramExpr = "param" + cpcn.getId();
          // The param can be assumed to be a list at this point since it was created as an output
          // variable.
          valuePyExpr = new PyListExpr(paramExpr, Integer.MAX_VALUE);
        }

        // Param content nodes require a content kind in strict autoescaping, so the content must be
        // wrapped as SanitizedContent.
        valuePyExpr = PyExprUtils.wrapAsSanitizedContent(cpcn.getContentKind(),
            valuePyExpr.toPyString());

        additionalParams.put(key, valuePyExpr);
      }
    }

    PyExpr additionalParamsExpr = PyExprUtils.convertMapToPyExpr(additionalParams);

    // Cases 2 and 3: Additional params with and without original data to pass.
    if (callNode.isPassingData()) {
      return "runtime.merge_into_dict(" + additionalParamsExpr.getText() + ", " + dataToPass + ")";
    } else {
      return additionalParamsExpr.getText();
    }
  }

  /**
   * Escaping directives might apply to the output of the call node, so wrap the output with all
   * required directives.
   *
   * @param callExpr The expression text of the call itself.
   * @param directiveNames The list of the directive names to be applied to the call.
   * @return A PyExpr containing the call expression with all directives applied.
   */
  private PyExpr escapeCall(String callExpr, ImmutableList<String> directiveNames) {
    PyExpr escapedExpr = new PyExpr(callExpr, Integer.MAX_VALUE);
    if (directiveNames.isEmpty()) {
      return escapedExpr;
    }

    // Successively wrap each escapedExpr in various directives.
    for (String directiveName : directiveNames) {
      SoyPySrcPrintDirective directive = soyPySrcDirectivesMap.get(directiveName);
      Preconditions.checkNotNull(directive,
          "Autoescaping produced a bogus directive: %s", directiveName);
      escapedExpr = directive.applyForPySrc(escapedExpr, ImmutableList.<PyExpr>of());
    }
    return escapedExpr;
  }
}
