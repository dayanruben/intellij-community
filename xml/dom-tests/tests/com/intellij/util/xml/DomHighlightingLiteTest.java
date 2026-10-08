// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.xml;

import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInsight.daemon.impl.AnnotationSessionImpl;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.InspectionProfile;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.lang.annotation.Annotator;
import com.intellij.mock.MockInspectionProfile;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiType;
import com.intellij.psi.xml.XmlElement;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.util.xml.highlighting.BasicDomElementsInspection;
import com.intellij.util.xml.highlighting.DomElementAnnotationHolder;
import com.intellij.util.xml.highlighting.DomElementAnnotationHolderImpl;
import com.intellij.util.xml.highlighting.DomElementAnnotationsManagerImpl;
import com.intellij.util.xml.highlighting.DomElementsInspection;
import com.intellij.util.xml.highlighting.DomElementsProblemsHolder;
import com.intellij.util.xml.highlighting.DomElementsProblemsHolderImpl;
import com.intellij.util.xml.highlighting.DomHighlightStatus;
import com.intellij.util.xml.highlighting.DomHighlightingHelper;
import com.intellij.util.xml.highlighting.DomHighlightingHelperImpl;
import com.intellij.util.xml.highlighting.MockAnnotatingDomInspection;
import com.intellij.util.xml.highlighting.MockDomInspection;
import com.intellij.util.xml.impl.DefaultDomAnnotator;
import com.intellij.util.xml.impl.DomTestFixture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;

import static com.intellij.testFramework.EdtTestUtil.runInEdtAndWait;
import static com.intellij.util.xml.impl.DomTestFixtures.domModuleFixture;
import static com.intellij.util.xml.impl.DomTestFixtures.domTestFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestApplication
public class DomHighlightingLiteTest {
  private static final TestFixture<Module> moduleFixture = domModuleFixture();
  private final TestFixture<DomTestFixture> domFixture = domTestFixture(moduleFixture);
  private DomElementAnnotationsManagerImpl myAnnotationsManager;
  private MockDomFileElement myElement;
  private MockInspectionProfile myInspectionProfile;

  @BeforeEach
  void setUp() {
    runInEdtAndWait(() -> {
      myInspectionProfile = new MockInspectionProfile();
      myAnnotationsManager = new DomElementAnnotationsManagerImpl(domFixture.get().getProject()) {

        @Override
        protected InspectionProfile getInspectionProfile(final DomFileElement fileElement) {
          return myInspectionProfile;
        }
      };

      final XmlFile file = domFixture.get().createXmlFile("<a/>");
      final MockDomElement rootElement = new MockDomElement() {
        @Override
        public @Nullable XmlElement getXmlElement() {
          return getXmlTag();
        }

        @Override
        public XmlTag getXmlTag() {
          return file.getRootTag();
        }

        @Override
        public @NotNull Type getDomElementType() {
          return DomElement.class;
        }
      };

      myElement = new MockDomFileElement() {
        @Override
        public @Nullable XmlElement getXmlElement() {
          return file;
        }

        @Override
        public @NotNull XmlFile getFile() {
          return file;
        }

        @Override
        public DomElement getParent() {
          return null;
        }

        @Override
        public @NotNull DomElement getRootElement() {
          return rootElement;
        }

        @Override
        public @NotNull Class<DomElement> getRootElementClass() {
          return DomElement.class;
        }

        @Override
        public boolean isValid() {
          return true;
        }
      };
    });
  }

  @Test
  public void testEmptyProblemDescriptorInTheBeginning() {
    runInEdtAndWait(() -> {
      assertEmptyHolder(myAnnotationsManager.getProblemHolder(myElement));
    });
  }

  private static void assertEmptyHolder(final DomElementsProblemsHolder holder) {
    assertFalse(holder instanceof DomElementsProblemsHolderImpl);
    assertTrue(holder.getAllProblems().isEmpty());
  }

  @Test
  public void testProblemDescriptorIsCreated() {
    runInEdtAndWait(() -> {
      myAnnotationsManager.appendProblems(myElement, createHolder(), MyDomElementsInspection.class);
      final DomElementsProblemsHolderImpl holder = assertNotEmptyHolder(myAnnotationsManager.getProblemHolder(myElement));
      assertTrue(holder.getAllProblems().isEmpty());
      assertTrue(holder.getAllProblems(new MyDomElementsInspection()).isEmpty());
    });
  }

  private DomElementAnnotationHolderImpl createHolder() {
    Annotator annotator = (element, holder) -> {};
    return AnnotationSessionImpl.computeWithSession(myElement.getFile(), false, annotator, holder -> new DomElementAnnotationHolderImpl(true, myElement, holder));
  }

  private static DomElementsProblemsHolderImpl assertNotEmptyHolder(final DomElementsProblemsHolder holder1) {
    return assertInstanceOf(DomElementsProblemsHolderImpl.class, holder1);
  }

  @Test
  public void testInspectionMarkedAsPassedAfterAppend() {
    runInEdtAndWait(() -> {
      myAnnotationsManager.appendProblems(myElement, createHolder(), MyDomElementsInspection.class);
      final DomElementsProblemsHolderImpl holder = (DomElementsProblemsHolderImpl)myAnnotationsManager.getProblemHolder(myElement);
      assertTrue(holder.isInspectionCompleted(MyDomElementsInspection.class));
      assertFalse(holder.isInspectionCompleted(DomElementsInspection.class));
    });
  }

  @Test
  public void testHolderRecreationAfterChange() {
    runInEdtAndWait(() -> {
      myAnnotationsManager.appendProblems(myElement, createHolder(), MyDomElementsInspection.class);
      assertTrue(myAnnotationsManager.isHolderUpToDate(myElement));
      final DomElementsProblemsHolder holder = myAnnotationsManager.getProblemHolder(myElement);

      domFixture.get().getPsiManager().dropPsiCaches();
      assertFalse(myAnnotationsManager.isHolderUpToDate(myElement));

      myAnnotationsManager.appendProblems(myElement, createHolder(), MyDomElementsInspection.class);
      assertNotSame(holder, assertNotEmptyHolder(myAnnotationsManager.getProblemHolder(myElement)));
    });
  }

  @Test
  public void testMockDomInspection() {
    runInEdtAndWait(() -> {
      myElement.setFileDescription(new MyNonHighlightingDomFileDescription());
      assertInstanceOf(MockDomInspection.class, myAnnotationsManager.getMockInspection(myElement));
    });
  }

  @Test
  public void testMockAnnotatingDomInspection() {
    runInEdtAndWait(() -> {
      myElement.setFileDescription(new DomFileDescription<>(DomElement.class, "a"));
      assertInstanceOf(MockAnnotatingDomInspection.class, myAnnotationsManager.getMockInspection(myElement));
    });
  }

  @Test
  public void testNoMockInspection() {
    runInEdtAndWait(() -> {
      myElement.setFileDescription(new MyNonHighlightingDomFileDescription());
      myInspectionProfile.setInspectionTools(Collections.singletonList(new LocalInspectionToolWrapper(new MyDomElementsInspection())));
      assertNull(myAnnotationsManager.getMockInspection(myElement));
    });
  }

  @Test
  public void testDefaultAnnotator() {
    runInEdtAndWait(() -> {
      final DefaultDomAnnotator annotator = new DefaultDomAnnotator() {
        @Override
        protected @NotNull DomElementAnnotationsManagerImpl getAnnotationsManager(final @NotNull Project project) {
          return myAnnotationsManager;
        }
      };
      final StringBuilder s = new StringBuilder();
      AnnotationSessionImpl.computeWithSession(myElement.getFile(), false, annotator, annotationHolder -> {
        final MyDomElementsInspection inspection = new MyDomElementsInspection() {

          @Override
          public void checkFileElement(final @NotNull DomFileElement fileElement, final @NotNull DomElementAnnotationHolder holder) {
            s.append("visited");
          }
        };
        annotator.runInspection(inspection, myElement, annotationHolder);
        assertEquals("visited", s.toString());
        final DomElementsProblemsHolderImpl holder = assertNotEmptyHolder(myAnnotationsManager.getProblemHolder(myElement));
        assertTrue(((Collection<?>)annotationHolder).isEmpty());

        annotator.runInspection(inspection, myElement, annotationHolder);
        assertEquals("visited", s.toString());
        assertSame(holder, assertNotEmptyHolder(myAnnotationsManager.getProblemHolder(myElement)));
        assertTrue(((Collection<?>)annotationHolder).isEmpty());

        return null;
      });
    });
  }

  @Test
  public void testHighlightStatus_MockDomInspection() {
    runInEdtAndWait(() -> {
      myElement.setFileDescription(new MyNonHighlightingDomFileDescription());
      assertEquals(DomHighlightStatus.NONE, myAnnotationsManager.getHighlightStatus(myElement));

      myAnnotationsManager.appendProblems(myElement, createHolder(), MockDomInspection.getInspection());
      assertEquals(DomHighlightStatus.INSPECTIONS_FINISHED, myAnnotationsManager.getHighlightStatus(myElement));
    });
  }
  @Test
  public void testHighlightStatus_MockAnnotatingDomInspection() {
    runInEdtAndWait(() -> {
      myElement.setFileDescription(new DomFileDescription<>(DomElement.class, "a"));

      myAnnotationsManager.appendProblems(myElement, createHolder(), MockAnnotatingDomInspection.getInspection());
      assertEquals(DomHighlightStatus.INSPECTIONS_FINISHED, myAnnotationsManager.getHighlightStatus(myElement));
    });
  }

  @Test
  public void testHighlightStatus_OtherInspections() {
    runInEdtAndWait(() -> {
      myElement.setFileDescription(new DomFileDescription<>(DomElement.class, "a"));
      final MyDomElementsInspection inspection = new MyDomElementsInspection() {

        @Override
        public ProblemDescriptor[] checkFile(@NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
          myAnnotationsManager.appendProblems(myElement, createHolder(), this.getClass());
          return ProblemDescriptor.EMPTY_ARRAY;
        }

        @Override
        public void checkFileElement(final @NotNull DomFileElement fileElement, final @NotNull DomElementAnnotationHolder holder) {
        }
      };
      registerInspectionKey(inspection);
      myInspectionProfile.setInspectionTools(Collections.singletonList(new LocalInspectionToolWrapper(inspection)));

      myAnnotationsManager.appendProblems(myElement, createHolder(), MockAnnotatingDomInspection.getInspection());
      assertEquals(DomHighlightStatus.ANNOTATORS_FINISHED, myAnnotationsManager.getHighlightStatus(myElement));

      myAnnotationsManager.appendProblems(myElement, createHolder(), inspection.getClass());
      assertEquals(DomHighlightStatus.INSPECTIONS_FINISHED, myAnnotationsManager.getHighlightStatus(myElement));
    });
  }

  private static void registerInspectionKey(MyDomElementsInspection inspection) {
    final String shortName = inspection.getShortName();
    HighlightDisplayKey.findOrRegister(shortName, shortName, inspection.getID());
  }

  @Test
  public void testHighlightStatus_OtherInspections2() {
    runInEdtAndWait(() -> {
      myElement.setFileDescription(new DomFileDescription<>(DomElement.class, "a"));
      MyDomElementsInspection inspection = new MyDomElementsInspection() {
        @Override
        public ProblemDescriptor[] checkFile(@NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
          myAnnotationsManager.appendProblems(myElement, createHolder(), this.getClass());
          return ProblemDescriptor.EMPTY_ARRAY;
        }

        @Override
        public void checkFileElement(final @NotNull DomFileElement fileElement, final @NotNull DomElementAnnotationHolder holder) {
        }
      };
      registerInspectionKey(inspection);
      LocalInspectionToolWrapper toolWrapper = new LocalInspectionToolWrapper(inspection);
      myInspectionProfile.setInspectionTools(Collections.singletonList(toolWrapper));
      myInspectionProfile.setEnabled(toolWrapper, false);

      myAnnotationsManager.appendProblems(myElement, createHolder(), MockAnnotatingDomInspection.getInspection());
      assertEquals(DomHighlightStatus.INSPECTIONS_FINISHED, myAnnotationsManager.getHighlightStatus(myElement));
    });
  }

  @Test
  public void testRequiredAttributeWithoutAttributeValue() {
    runInEdtAndWait(() -> {
      myElement.setFileDescription(new DomFileDescription<>(DomElement.class, "a"));
      final MyElement element = domFixture.get().createElement("<a id />", MyElement.class);
      new MyBasicDomElementsInspection().checkDomElement(element.getId(), createHolder(), DomHighlightingHelperImpl.INSTANCE);
    });
  }

  private static class MyDomElementsInspection extends DomElementsInspection<DomElement> {
    MyDomElementsInspection() {
      super(DomElement.class);
    }

    @Override
    public @NotNull String getGroupDisplayName() {
      throw new UnsupportedOperationException("Method getGroupDisplayName is not yet implemented in " + getClass().getName());
    }

    @Override
    public @NotNull String getDisplayName() {
      throw new UnsupportedOperationException("Method getDisplayName is not yet implemented in " + getClass().getName());
    }

    @Override
    public @NotNull String getShortName() {
      return "xxx";
    }
  }

  private static class MyBasicDomElementsInspection extends BasicDomElementsInspection<DomElement> {
    MyBasicDomElementsInspection() {
      super(DomElement.class);
    }

    @Override
    public @NotNull String getGroupDisplayName() {
      throw new UnsupportedOperationException("Method getGroupDisplayName is not yet implemented in " + getClass().getName());
    }

    @Override
    public @NotNull String getDisplayName() {
      throw new UnsupportedOperationException("Method getDisplayName is not yet implemented in " + getClass().getName());
    }

    @Override
    protected void checkDomElement(final @NotNull DomElement element, final @NotNull DomElementAnnotationHolder holder, final @NotNull DomHighlightingHelper helper) {
      super.checkDomElement(element, holder, helper);
    }

    @Override
    public @NotNull String getShortName() {
      return "xxx";
    }
  }


  private static class MyNonHighlightingDomFileDescription extends DomFileDescription<DomElement> {
    MyNonHighlightingDomFileDescription() {
      super(DomElement.class, "a");
    }

    @Override
    public boolean isAutomaticHighlightingEnabled() {
      return false;
    }
  }

  public interface MyElement extends DomElement {
    @Convert(soft=true, value=JvmPsiTypeConverter.class)
    @Required GenericAttributeValue<PsiType> getId();
  }
}
