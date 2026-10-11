package com.intellij.mermaid.lang.lexer

import com.intellij.testFramework.junit5.fixture.TestFixtures
import org.junit.jupiter.api.Test

@TestFixtures
class TimelineTest {
  private val lexer by mermaidLexerFixture("timeline")

  @Test
  fun `test simple timeline`() {
    val content = """
    timeline
      title History of Social Media Platform
      2002 : LinkedIn
      2004 : Facebook
           : Google
      2005 : Youtube
      2006 : Twitter
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test complex timeline`() {
    val content = """
    timeline
      title Timeline of Industrial Revolution
      section 17th-20th century
        Industry 1.0 : Machinery, Water power, Steam <br>power
        Industry 2.0 : Electricity, Internal combustion engine, Mass production
        Industry 3.0 : Electronics, Computers, Automation
      section 21st century
        Industry 4.0 : Internet, Robotics, Internet of Things
        Industry 5.0 : Artificial intelligence, Big data,3D printing
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test with ignored tokens`() {
    val content = """
    timeline
      title Timeline of Industrial Rev#olution
      section 17th-20th #century
        Industry 1.0 : Machinery, Water power#, Steam <br>power
        Industry 2.0 : Electricity, Internal combustion engine
                     #: Mass production
        Industry 3.0 #: Electronics, Computers, Automation
      section 21st century
        #Industry 4.0 : Internet, Robotics, Internet of Things
        Industry 5.0 : Artificial intelligence, Big data,3D printing
    """.trimIndent()
    lexer.doTest(content)
  }

}
