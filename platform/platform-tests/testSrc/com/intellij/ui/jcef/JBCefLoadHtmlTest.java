// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.jcef;

import com.intellij.testFramework.ApplicationRule;
import com.intellij.ui.scale.TestScaleHelper;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandler;
import org.cef.network.CefRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;

import static com.intellij.ui.jcef.JBCefTestHelper.invokeAndWaitForLatch;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests that {@link JBCefBrowser#loadHTML(String, String)} can load html that references JS via "file://"
 * and the JS is uploaded from disk and executed.
 *
 * @author tav
 */
public class JBCefLoadHtmlTest {
  static {
    TestScaleHelper.setSystemProperty("java.awt.headless", "false");
  }

  @ClassRule public static final ApplicationRule appRule = new ApplicationRule();

  static final String HTML_URL = "file://page.html";
  static final String JS_URL = "file://script.js";
  static final String HTML = """
    <html>
    <body>

    === JBCefLoadHtmlTest ===
    <script src="#script_url#"></script>

    </body>
    </html>""".replaceAll("#script_url#", JS_URL);

  static final CountDownLatch LATCH = new CountDownLatch(1);
  static volatile boolean testPassed;

  JBCefBrowser browser;

  @Before
  public void before() {
    TestScaleHelper.assumeStandalone();
  }

  @After
  public void after() {
    TestScaleHelper.restoreProperties();
  }

  @Test
  public void test() throws InterruptedException, InvocationTargetException {
    SwingUtilities.invokeAndWait(() -> {
      browser = new JBCefBrowser();
      browser.getJBCefClient().addLoadHandler(new CefLoadHandler() {
        @Override
        public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
          System.out.println("JBCefLoadHtmlTest.onLoadingStateChange");
        }

        @Override
        public void onLoadStart(CefBrowser browser, CefFrame frame, CefRequest.TransitionType transitionType) {
          System.out.println("JBCefLoadHtmlTest.onLoadStart");
        }

        @Override
        public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
          System.out.println("JBCefLoadHtmlTest.onLoadEnd");
        }

        @Override
        public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode, String errorText, String failedUrl) {
          System.out.println("JBCefLoadHtmlTest.onLoadError");
        }
      }, browser.getCefBrowser());

      JBCefJSQuery jsQuery = JBCefJSQuery.create((JBCefBrowserBase)browser);

      jsQuery.addHandler(result -> {
        System.out.println("JS callback result: " + result);
        testPassed = true;
        LATCH.countDown();
        return null;
      });

      JBCefFileSchemeHandlerFactory.registerLoadHTMLRequest(browser.myCefBrowser, jsQuery.inject("'hello'"), JS_URL);
    });
    assertNotNull(browser);


    invokeAndWaitForLatch(LATCH, "loadHTML -> wait js callback", () -> {
      JFrame frame = new JFrame(JBCefLoadHtmlTest.class.getName());
      frame.setSize(640, 480);
      frame.setLocationRelativeTo(null);
      frame.add(browser.getComponent());
      frame.addWindowListener(new WindowAdapter() {
        @Override
        public void windowOpened(WindowEvent e) {
          browser.loadHTML(HTML, HTML_URL);
        }
      });
      frame.setVisible(true);
    });

    assertTrue(testPassed);
  }
}
