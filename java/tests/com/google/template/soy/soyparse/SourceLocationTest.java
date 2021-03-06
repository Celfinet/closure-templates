/*
 * Copyright 2010 Google Inc.
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

package com.google.template.soy.soyparse;

import com.google.common.base.Joiner;
import com.google.template.soy.SoyFileSetParserBuilder;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.FixedIdGenerator;
import com.google.template.soy.base.internal.SoyFileKind;
import com.google.template.soy.base.internal.SoyFileSupplier;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.types.SoyTypeRegistry;

import junit.framework.TestCase;

/**
 * Tests that the Soy file and template parsers properly embed source locations.
 */
public final class SourceLocationTest extends TestCase {
  public void testLocationsInParsedContent() throws Exception {
    assertSourceLocations(
        Joiner.on('\n').join(
            "SoyFileSetNode                 @ unknown",
            "  SoyFileNode                  @ /example/file.soy",
            "    TemplateBasicNode          @ /example/file.soy:1:1",
            "      RawTextNode              @ /example/file.soy:2:3",
            "      PrintNode                @ /example/file.soy:4:3",
            "      RawTextNode              @ /example/file.soy:5:1",
            "      CallBasicNode            @ /example/file.soy:7:3",
            "    TemplateBasicNode          @ /example/file.soy:9:1",
            "      RawTextNode              @ /example/file.soy:10:3",
            ""),
        Joiner.on('\n').join(
            "{template foo autoescape=\"deprecated-noncontextual\"}",     // 1
            "  Hello",            // 2
            "  {lb}",             // 3
            "  {print $world}",   // 4
            "  {rb}!",            // 5
            "",                   // 6
            "  {call bar /}",     // 7
            "{/template}",        // 8
            "{template bar autoescape=\"deprecated-noncontextual\"}",     // 9
            "  Gooodbye",         // 10
            "{/template}"         // 11
            )
        );
  }

  public void testSwitches() throws Exception {
    assertSourceLocations(
        Joiner.on('\n').join(
            "SoyFileSetNode                 @ unknown",
            "  SoyFileNode                  @ /example/file.soy",
            "    TemplateBasicNode          @ /example/file.soy:1:1",
            "      RawTextNode              @ /example/file.soy:2:3",
            "      SwitchNode               @ /example/file.soy:3:3",
            "        SwitchCaseNode         @ /example/file.soy:4:5",
            "          RawTextNode          @ /example/file.soy:5:1",
            "        SwitchCaseNode         @ /example/file.soy:6:5",
            "          RawTextNode          @ /example/file.soy:7:1",
            "        SwitchCaseNode         @ /example/file.soy:8:5",
            "          RawTextNode          @ /example/file.soy:9:1",
            "        SwitchDefaultNode      @ /example/file.soy:10:5",
            "          RawTextNode          @ /example/file.soy:11:1",
            "      RawTextNode              @ /example/file.soy:13:1",
            ""),
        Joiner.on('\n').join(
            "{template foo autoescape=\"deprecated-noncontextual\"}",  // 1
            "  Hello,",        // 2
            "  {switch $i}",   // 3
            "    {case 0}",    // 4
            "      Mercury",   // 5
            "    {case 1}",    // 6
            "      Venus",     // 7
            "    {case 2}",    // 8
            "      Mars",      // 9
            "    {default}",   // 10
            "      Gassy",     // 11
            "  {/switch}",     // 12
            "  !",             // 13
            "{/template}",     // 14
            "")
        );
  }

  public void testForLoop() throws Exception {
    assertSourceLocations(
        Joiner.on('\n').join(
            "SoyFileSetNode                 @ unknown",
            "  SoyFileNode                  @ /example/file.soy",
            "    TemplateBasicNode          @ /example/file.soy:1:1",
            "      RawTextNode              @ /example/file.soy:2:3",
            "      ForNode                  @ /example/file.soy:3:3",
            "        RawTextNode            @ /example/file.soy:4:1",
            "        PrintNode              @ /example/file.soy:5:5",
            "      RawTextNode              @ /example/file.soy:7:1",
            ""),
        Joiner.on('\n').join(
            "{template foo autoescape=\"deprecated-noncontextual\"}",                   // 1
            "  Hello",                          // 2
            "  {for $i in range($s, $e, $t)}",  // 3
            "    ,",                            // 4
            "    {print $planet[$i]}",          // 5
            "  {/for}",                         // 6
            "  !",                              // 7
            "{/template}",                      // 8
            "")
        );
  }

  public void testForeachLoop() throws Exception {
    assertSourceLocations(
        Joiner.on('\n').join(
            "SoyFileSetNode                 @ unknown",
            "  SoyFileNode                  @ /example/file.soy",
            "    TemplateBasicNode          @ /example/file.soy:1:1",
            "      RawTextNode              @ /example/file.soy:2:3",
            "      ForeachNode              @ /example/file.soy:3:3",
            "        ForeachNonemptyNode    @ /example/file.soy:3:3",
            "          RawTextNode          @ /example/file.soy:4:1",
            "          PrintNode            @ /example/file.soy:5:5",
            "        ForeachIfemptyNode     @ /example/file.soy:6:3",
            "          RawTextNode          @ /example/file.soy:7:1",
            "      RawTextNode              @ /example/file.soy:9:1",
            ""),
        Joiner.on('\n').join(
            "{template foo autoescape=\"deprecated-noncontextual\"}",                   // 1
            "  Hello",                          // 2
            "  {foreach $planet in $planets}",  // 3
            "    ,",                            // 4
            "    {print $planet[$i]}",          // 5
            "  {ifempty}",                      // 6
            "    lifeless interstellar void",   // 7
            "  {/foreach}",                     // 8
            "  !",                              // 9
            "{/template}",                      // 10
            "")
        );
  }

  public void testConditional() throws Exception {
    assertSourceLocations(
        Joiner.on('\n').join(
            "SoyFileSetNode                 @ unknown",
            "  SoyFileNode                  @ /example/file.soy",
            "    TemplateBasicNode          @ /example/file.soy:1:1",
            "      RawTextNode              @ /example/file.soy:2:3",
            "      IfNode                   @ /example/file.soy:3:3",
            "        IfCondNode             @ /example/file.soy:3:3",
            "          RawTextNode          @ /example/file.soy:4:1",
            "        IfCondNode             @ /example/file.soy:5:3",
            "          RawTextNode          @ /example/file.soy:6:1",
            "        IfElseNode             @ /example/file.soy:7:3",
            "          RawTextNode          @ /example/file.soy:8:1",
            "      RawTextNode              @ /example/file.soy:10:1",
            ""),
        Joiner.on('\n').join(
            "{template foo autoescape=\"deprecated-noncontextual\"}",                 // 1
            "  Hello,",                       // 2
            "  {if $skyIsBlue}",              // 3
            "    Earth",                      // 4
            "  {elseif $isReallyReallyHot}",  // 5
            "    Venus",                      // 6
            "  {else}",                       // 7
            "    Cincinatti",                 // 8
            "  {/if}",                        // 9
            "  !",                            // 10
            "{/template}",                    // 11
            "")
        );
  }

  public void testDoesntAccessPastEnd() {
    // Make sure that if we have a token stream that ends abruptly, we don't
    // look for a line number and break in a way that suppresses the real error
    // message.
    // JavaCC is pretty good about never using null as a token value.
    ErrorReporterImpl errorReporter = new ErrorReporterImpl();
    SoyFileSetParserBuilder.forSuppliers(
          SoyFileSupplier.Factory.create(
              "{template t autoescape=\"deprecated-noncontextual\"}\nHello, World!\n",
              SoyFileKind.SRC, "borken.soy"))
          .doRunInitialParsingPasses(false)
          .errorReporter(errorReporter)
          .parse();
    assertEquals(1, errorReporter.getErrors().size());
  }

  public void testAdditionalSourceLocationInfo() throws Exception {
    String template =
        "{namespace ns}\n"
            + "{template .t}\n"
            + "  hello, world\n"
            + "{/template}\n";
    TemplateNode templateNode = new SoyFileParser(
        new SoyTypeRegistry(),
        new FixedIdGenerator(),
        template,
        SoyFileKind.SRC,
        "/example/file.soy")
        .parseSoyFile()
        .getChild(0);
    SourceLocation location = templateNode.getSourceLocation();
    assertEquals(2, location.getLineNumber());
    assertEquals(1, location.getBeginColumn());
    assertEquals(4, location.getEndLine());
    assertEquals(11, location.getEndColumn());
  }


  private void assertSourceLocations(String asciiArtExpectedOutput, String soySourceCode) {
    SoyFileSetNode soyTree = SoyFileSetParserBuilder.forSuppliers(
        SoyFileSupplier.Factory.create(soySourceCode, SoyFileKind.SRC, "/example/file.soy"))
        .doRunInitialParsingPasses(false)
        .parse();
    String actual = new AsciiArtVisitor().exec(soyTree);
    assertEquals(asciiArtExpectedOutput, actual);
  }


  /**
   * Generates a concise readable summary of a soy tree and its source locations.
   */
  private static class AsciiArtVisitor extends AbstractSoyNodeVisitor<String> {
    final StringBuilder sb = new StringBuilder();
    int depth;

    private AsciiArtVisitor() {
      super(ExplodingErrorReporter.get());
    }

    @Override public String exec(SoyNode node) {
      visit(node);
      return sb.toString();
    }

    @Override protected void visitSoyNode(SoyNode node) {
      // Output a header like:
      //   <indent> <node class>                    @ <location>
      // where indent is 2 spaces per level, and the @ sign is indented to the 31st column.
      for (int indent = depth; --indent >= 0;) {
        sb.append("  ");
      }
      String typeName = node.getClass().getSimpleName();
      sb.append(typeName);
      int pos = typeName.length() + 2 * depth;
      while (pos < 30) {
        sb.append(' ');
        ++pos;
      }
      sb.append(" @ ").append(node.getSourceLocation()).append('\n');

      if (node instanceof ParentSoyNode<?>) {
        ++depth;
        visitChildren((ParentSoyNode<?>) node);
        --depth;
      }
    }
  }
}
