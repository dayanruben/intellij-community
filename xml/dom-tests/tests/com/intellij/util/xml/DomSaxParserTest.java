/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.util.xml;

import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static com.intellij.testFramework.junit5.fixture.FixturesKt.projectFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;

@TestApplication
public class DomSaxParserTest {
  private static final TestFixture<Project> projectFixture = projectFixture();

  @Test
  public void testGetRootTagNameWithoutNamespace() {
    assertData("<root>", "root", null, null, null);
  }

  @Test
  public void testGetRootTagNameWithNamespaceWithEmptyPrefix() {
    assertData("<root xmlns=\"foo\">", "root", "foo", null, null);
  }

  @Test
  public void testGetRootTagNameWithUnfinishedAttribute() {
    ReadAction.runBlocking(() -> {
      XmlFile file = createXmlFile("<root xmlns=\"foo\" aaa>");
      ensureParsed(file);
      final XmlFileHeader header = DomService.getInstance().getXmlFileHeader(file);
      assertEquals(new XmlFileHeader("root", "foo", null, null), header);
    });
  }

  @Test
  public void testGetRootTagNameWithNamespaceWithNonEmptyPrefix() {
    assertData("<bar:root xmlns=\"foo\" xmlns:bar=\"b\">", "root", "b", null, null);
  }

  @Test
  public void testGetRootTagNameWithDtdNamespace() {
    assertData("""
                 <!DOCTYPE ejb-jar PUBLIC
                 "-//Sun Microsystems, Inc.//DTD Enterprise JavaBeans 2.0//EN"
                 "http://java.sun.com/dtd/ejb-jar_2_0.dtd"><root>""", "root", null, "-//Sun Microsystems, Inc.//DTD Enterprise JavaBeans 2.0//EN", "http://java.sun.com/dtd/ejb-jar_2_0.dtd");
  }

  @Test
  public void testGetRootTagNameWithDtdNamespace2() {
    assertData("""
                 <?xml version="1.0" encoding="UTF-8"?>
                 <!DOCTYPE ejb-jar PUBLIC
                 "-//Sun Microsystems, Inc.//DTD Enterprise JavaBeans 2.0//EN"
                 "http://java.sun.com/dtd/ejb-jar_2_0.dtd"><root>""", "root", null, "-//Sun Microsystems, Inc.//DTD Enterprise JavaBeans 2.0//EN", "http://java.sun.com/dtd/ejb-jar_2_0.dtd");
  }

  @Test
  public void testNoTag() {
    assertData("aaaaaaaaaaaaaaaaaaaaa", null, null, null, null);
  }

  @Test
  public void testEmptyFile() {
    assertData("", null, null, null, null);
  }

  @Test
  public void testInvalidContent() {
    assertData("<?xmlmas8v6708986><OKHD POH:&*$%*&*I8yo9", null, null, null, null);
  }

  @Test
  public void testInvalidContent2() {
    assertData("?xmlmas8v6708986><OKHD POH:&*$%*&*I8yo9", null, null, null, null);
  }

  private static void ensureParsed(PsiFile file) {
    file.getNode().getFirstChildNode();
  }

  @Test
  public void testInvalidContent3() {
    assertData("<?xmlmas8v67089", null, null, null, null);
  }

  @Test
  public void testSubtag() {
    assertData("<root><foo/>", "root", null, null, null);
  }

  @Test
  public void testSpring() {
    assertData("""
                 <?xml version="1.0" encoding="gbk"?>


                 <beans xmlns="http://www.springframework.org/schema/beans"
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        xmlns:aop="http://www.springframework.org/schema/aop"
                        xmlns:tx="http://www.springframework.org/schema/tx"
                        xsi:schemaLocation="http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans-2.0.xsd
                            http://www.springframework.org/schema/aop http://www.springframework.org/schema/aop/spring-aop-2.0.xsd
                            http://www.springframework.org/schema/tx http://www.springframework.org/schema/tx/spring-tx-2.0.xsd">
                 </beans>""", "beans", "http://www.springframework.org/schema/beans", null, null);
  }

  @Test
  public void testInternalDtd() {
    assertData("""
                 <?xml version="1.0"?>
                 <!DOCTYPE\s
                         hibernate-mapping SYSTEM
                 \t\t\t"http://hibernate.sourceforge.net/hibernate-mapping-3.0.dtd"
                 [
                 <!ENTITY % globals SYSTEM "classpath://auction/persistence/globals.dtd">
                 %globals;
                 ]><a/>""", "a", null, null, "http://hibernate.sourceforge.net/hibernate-mapping-3.0.dtd");
  }

  private static void assertData(final String start, @Nullable final String localName, @Nullable String namespace, @Nullable String publicId, @Nullable String systemId) {
    XmlFileHeader expected = new XmlFileHeader(localName, namespace, publicId, systemId);

    ReadAction.runBlocking(() -> {
      XmlFile file = createXmlFile(start);
      assert !file.getNode().isParsed();
      assertEquals(expected, DomService.getInstance().getXmlFileHeader(file));

      ensureParsed(file);
      assert file.getNode().isParsed();
      assertEquals(expected, DomService.getInstance().getXmlFileHeader(file));
    });
  }

  private static XmlFile createXmlFile(String text) {
    return (XmlFile)PsiFileFactory.getInstance(projectFixture.get()).createFileFromText("a.xml", XMLLanguage.INSTANCE, text, false, false, false);
  }
}
