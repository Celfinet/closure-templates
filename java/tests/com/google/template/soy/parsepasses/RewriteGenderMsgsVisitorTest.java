/*
 * Copyright 2012 Google Inc.
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

package com.google.template.soy.parsepasses;

import static com.google.common.truth.Truth.assertThat;

import com.google.template.soy.SoyFileSetParserBuilder;
import com.google.template.soy.base.SoySyntaxException;
import com.google.template.soy.msgs.internal.MsgUtils;
import com.google.template.soy.shared.SharedTestUtils;
import com.google.template.soy.soyparse.ErrorReporter;
import com.google.template.soy.soyparse.ErrorReporterImpl;
import com.google.template.soy.soyparse.ExplodingErrorReporter;
import com.google.template.soy.soytree.MsgNode;
import com.google.template.soy.soytree.SoyFileSetNode;

import junit.framework.TestCase;

/**
 * Unit tests for RewriteGenderMsgsVisitor.
 *
 */
public class RewriteGenderMsgsVisitorTest extends TestCase {


  public void testCannotMixGendersAndSelect() {

    String soyCode = "" +
        "{msg genders=\"$userGender\" desc=\"Button text.\"}\n" +
        "  {select $targetGender}\n" +
        "    {case 'female'}Reply to her\n" +
        "    {case 'male'}Reply to him\n" +
        "    {default}Reply to them\n" +
        "  {/select}\n" +
        "{/msg}\n";
    try {
      SoyFileSetParserBuilder.forTemplateContents(soyCode).parse();
      fail();
    } catch (SoySyntaxException sse) {
      assertThat(sse.getMessage())
          .contains(
              "Cannot mix 'genders' attribute with 'select' command in the same message. Please use"
              + " one or the other only.");
    }
  }


  public void testErrorIfCannotGenNoncollidingBaseNames() {

    String soyCode = "" +
        "{msg genders=\"$userGender, $gender\" desc=\"Button text.\"}\n" +
        "  You joined {$owner}'s community.\n" +
        "{/msg}\n";
    try {
      SoyFileSetParserBuilder.forTemplateContents(soyCode).parse();
      fail();
    } catch (SoySyntaxException sse) {
      assertThat(sse.getMessage())
          .contains("Cannot generate noncolliding base names for msg placeholders and/or vars:"
              + " found colliding expressions \"$gender\" and \"$userGender\".");
    }
  }


  public void testMaxThreeGenders() {
    String soyCode = "" +
        "{msg genders=\"$userGender, $targetGender1, $targetGender2, $groupOwnerGender\"" +
        "    desc=\"...\"}\n" +
        "  You added {$targetName1} and {$targetName2} to {$groupOwnerName}'s group.\n" +
        "{/msg}\n";
    ErrorReporterImpl errorReporter = new ErrorReporterImpl();
    SoyFileSetParserBuilder.forTemplateContents(soyCode)
        .errorReporter(errorReporter)
        .parse();
      assertThat(errorReporter.getErrors()).hasSize(1);
      Throwable t = errorReporter.getErrors().asList().get(0);
      assertThat(t).isInstanceOf(SoySyntaxException.class);
      assertThat(t.getMessage()).contains(
          "Attribute 'genders' does not contain 1-3 expressions");
  }


  public void testMaxTwoGendersWithPlural() {

    String soyCode = "" +
        "{msg genders=\"$userGender, $gender1, $gender2\" desc=\"\"}\n" +
        "  {plural $numPhotos}\n" +
        "    {case 1}Find {$name1}'s face in {$name2}'s photo\n" +
        "    {default}Find {$name1}'s face in {$name2}'s photos\n" +
        "  {/plural}\n" +
        "{/msg}\n";
    try {
      SoyFileSetParserBuilder.forTemplateContents(soyCode).parse();
      fail();
    } catch (SoySyntaxException sse) {
      assertThat(sse.getMessage())
          .contains(
              "In a msg with 'plural', the 'genders' attribute can contain at most 2 expressions"
              + " (otherwise, combinatorial explosion would cause a gigantic generated message).");
    }
  }


  public void testRewriteSimple() {

    String soyCode = "" +
        "{msg genders=\"$userGender\" desc=\"Button text.\"}\n" +
        "  Save\n" +
        "{/msg}\n";

    ErrorReporter boom = ExplodingErrorReporter.get();
    SoyFileSetNode soyTree = SoyFileSetParserBuilder.forTemplateContents(soyCode)
        .errorReporter(boom)
        .doRunInitialParsingPasses(false)
        .parse();

    // Before.
    MsgNode msgBeforeRewrite = (MsgNode) SharedTestUtils.getNode(soyTree, 0, 0);
    assertThat(msgBeforeRewrite.toSourceString())
        .isEqualTo("{msg genders=\"$userGender\" desc=\"Button text.\"}Save");

    // Rewrite.
    new RewriteGenderMsgsVisitor(soyTree.getNodeIdGenerator(), boom).exec(soyTree);

    // After.
    MsgNode msgAfterRewrite = (MsgNode) SharedTestUtils.getNode(soyTree, 0, 0);
    assertEquals(
        // Note: Still has genders="..." in command text.
        "{msg genders=\"$userGender\" desc=\"Button text.\"}" +
            "{select $userGender}{case 'female'}Save{case 'male'}Save{default}Save{/select}",
        msgAfterRewrite.toSourceString());

    // ------ Test that it has same msg id as equivalent msg using 'select'. ------

    String soyCodeUsingSelect = "" +
        "{msg desc=\"Button text.\"}\n" +
        "  {select $userGender}\n" +
        "    {case 'female'}Save\n" +
        "    {case 'male'}Save\n" +
        "    {default}Save\n" +
        "  {/select}\n" +
        "{/msg}\n";
    SoyFileSetNode soyTreeUsingSelect =
        SoyFileSetParserBuilder.forTemplateContents(soyCodeUsingSelect).parse();
    MsgNode msgUsingSelect = (MsgNode) SharedTestUtils.getNode(soyTreeUsingSelect, 0, 0);
    assertThat(MsgUtils.computeMsgIdForDualFormat(msgAfterRewrite))
        .isEqualTo(MsgUtils.computeMsgIdForDualFormat(msgUsingSelect));
  }


  public void testRewriteWithPlural() {

    String soyCode = "" +
        "{msg genders=\"$userGender\" desc=\"...\"}\n" +
        "  {plural $num}{case 1}Send it{default}Send {$num}{/plural}\n" +
        "{/msg}\n";

    ErrorReporter boom = ExplodingErrorReporter.get();
    SoyFileSetNode soyTree = SoyFileSetParserBuilder.forTemplateContents(soyCode)
        .errorReporter(boom)
        .doRunInitialParsingPasses(false)
        .parse();

    // Before.
    MsgNode msgBeforeRewrite = (MsgNode) SharedTestUtils.getNode(soyTree, 0, 0);
    assertThat(msgBeforeRewrite.toSourceString())
        .isEqualTo("{msg genders=\"$userGender\" desc=\"...\"}"
            + "{plural $num}{case 1}Send it{default}Send {$num}{/plural}");

    // Rewrite.
    new RewriteGenderMsgsVisitor(soyTree.getNodeIdGenerator(), boom).exec(soyTree);

    // After.
    MsgNode msgAfterRewrite = (MsgNode) SharedTestUtils.getNode(soyTree, 0, 0);
    assertEquals(
        // Note: Still has genders="..." in command text.
        "{msg genders=\"$userGender\" desc=\"...\"}" +
            "{select $userGender}" +
            "{case 'female'}{plural $num}{case 1}Send it{default}Send {$num}{/plural}" +
            "{case 'male'}{plural $num}{case 1}Send it{default}Send {$num}{/plural}"  +
            "{default}{plural $num}{case 1}Send it{default}Send {$num}{/plural}"  +
            "{/select}",
        msgAfterRewrite.toSourceString());

    // ------ Test that it has same msg id as equivalent msg using 'select'. ------

    String soyCodeUsingSelect = "" +
        "{msg desc=\"...\"}\n" +
        "  {select $userGender}\n" +
        "    {case 'female'}{plural $num}{case 1}Send it{default}Send {$num}{/plural}\n" +
        "    {case 'male'}{plural $num}{case 1}Send it{default}Send {$num}{/plural}\n" +
        "    {default}{plural $num}{case 1}Send it{default}Send {$num}{/plural}\n" +
        "  {/select}\n" +
        "{/msg}\n";
    SoyFileSetNode soyTreeUsingSelect =
        SoyFileSetParserBuilder.forTemplateContents(soyCodeUsingSelect).parse();
    MsgNode msgUsingSelect = (MsgNode) SharedTestUtils.getNode(soyTreeUsingSelect, 0, 0);
    assertThat(MsgUtils.computeMsgIdForDualFormat(msgAfterRewrite))
        .isEqualTo(MsgUtils.computeMsgIdForDualFormat(msgUsingSelect));
  }


  public void testRewriteWithThreeGendersAndNoncollidingSelectVarNames() {

    String soyCode = "" +
        "{msg genders=\"$ij.userGender, $target[0].gender, $target[1].gender\" desc=\"...\"}\n" +
        "  You starred {$target[0].name}'s photo in {$target[1].name}'s album.\n" +
        "{/msg}\n";

    ErrorReporter boom = ExplodingErrorReporter.get();
    SoyFileSetNode soyTree = SoyFileSetParserBuilder.forTemplateContents(soyCode)
        .errorReporter(boom)
        .doRunInitialParsingPasses(false)
        .parse();

    // Before.
    MsgNode msgBeforeRewrite = (MsgNode) SharedTestUtils.getNode(soyTree, 0, 0);
    assertThat(msgBeforeRewrite.toSourceString())
        .isEqualTo(
            "{msg genders=\"$ij.userGender, $target[0].gender, $target[1].gender\" desc=\"...\"}"
            + "You starred {$target[0].name}'s photo in {$target[1].name}'s album.");

    // Rewrite.
    new RewriteGenderMsgsVisitor(soyTree.getNodeIdGenerator(), boom).exec(soyTree);

    // After.
    MsgNode msgAfterRewrite = (MsgNode) SharedTestUtils.getNode(soyTree, 0, 0);

    String expectedInnerSelectSrc = "" +
        "{select $target[1].gender phname=\"TARGET_1_GENDER\"}" +
          "{case 'female'}You starred {$target[0].name}'s photo in {$target[1].name}'s album." +
          "{case 'male'}You starred {$target[0].name}'s photo in {$target[1].name}'s album." +
          "{default}You starred {$target[0].name}'s photo in {$target[1].name}'s album." +
        "{/select}";
    String expectedMsgSrc = "" +
        // Note: Still has genders="..." in command text.
        "{msg genders=\"$ij.userGender, $target[0].gender, $target[1].gender\" desc=\"...\"}" +
          "{select $ij.userGender}" +  // note: 'phname' not specified because generated is same
            "{case 'female'}" +
              "{select $target[0].gender phname=\"TARGET_0_GENDER\"}" +
                "{case 'female'}" + expectedInnerSelectSrc +
                "{case 'male'}" + expectedInnerSelectSrc +
                "{default}" + expectedInnerSelectSrc +
              "{/select}" +
            "{case 'male'}" +
              "{select $target[0].gender phname=\"TARGET_0_GENDER\"}" +
                "{case 'female'}" + expectedInnerSelectSrc +
                "{case 'male'}" + expectedInnerSelectSrc +
                "{default}" + expectedInnerSelectSrc +
              "{/select}" +
            "{default}" +
              "{select $target[0].gender phname=\"TARGET_0_GENDER\"}" +
                "{case 'female'}" + expectedInnerSelectSrc +
                "{case 'male'}" + expectedInnerSelectSrc +
                "{default}" + expectedInnerSelectSrc +
              "{/select}" +
          "{/select}";
    assertThat(msgAfterRewrite.toSourceString()).isEqualTo(expectedMsgSrc);
  }

}
