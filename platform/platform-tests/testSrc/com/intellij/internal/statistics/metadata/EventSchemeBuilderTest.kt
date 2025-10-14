// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.internal.statistics.metadata

import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.FeatureUsageData
import com.intellij.internal.statistic.eventLog.events.EventField
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.internal.statistic.eventLog.events.ObjectEventField
import com.intellij.internal.statistic.eventLog.events.ObjectListEventField
import com.intellij.internal.statistic.eventLog.events.scheme.EventsSchemeBuilder
import com.intellij.internal.statistic.eventLog.events.scheme.EventsSchemeBuilder.buildEventsScheme
import com.intellij.internal.statistic.eventLog.events.scheme.EventsSchemeBuilder.pluginInfoFields
import com.intellij.internal.statistic.eventLog.events.scheme.FieldDescriptor
import com.intellij.internal.statistic.eventLog.events.scheme.GroupDescriptor
import com.intellij.internal.statistic.eventLog.events.scheme.PluginSchemeDescriptor
import com.intellij.internal.statistic.eventLog.events.scheme.RegisteredLogDescriptionsProcessor
import com.intellij.internal.statistic.eventLog.validator.ValidationResultType
import com.intellij.internal.statistic.eventLog.validator.rules.EventContext
import com.intellij.internal.statistic.eventLog.validator.rules.beans.EventGroupContextData
import com.intellij.internal.statistic.eventLog.validator.rules.impl.CustomValidationRule
import com.intellij.internal.statistic.eventLog.validator.rules.impl.CustomValidationRuleFactory
import com.intellij.internal.statistic.service.fus.collectors.CounterUsagesCollector
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EventSchemeBuilderTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    RegisteredLogDescriptionsProcessor.reset(false)
  }

  fun `test generate string field validated by regexp`() {
    doFieldTest(EventFields.StringValidatedByRegexpReference("count", "integer"), hashSetOf("{regexp#integer}"))
  }

  fun `test generate string field validated by enum`() {
    doFieldTest(EventFields.StringValidatedByEnum("system", "os"), hashSetOf("{enum#os}"))
  }

  fun `test generate string field validated by custom rule`() {
    val customValidationRule = TestCustomValidationRule("custom_rule")
    CustomValidationRule.EP_NAME.point.registerExtension(customValidationRule, testRootDisposable)
    doFieldTest(EventFields.StringValidatedByCustomRule("class", TestCustomValidationRule::class.java), hashSetOf("{util#custom_rule}"))
  }

  fun `test generate string field validated by custom rule factory`() {
    val testCustomValidationRuleFactory = TestCustomValidationRuleFactory("custom_rule_factory")
    CustomValidationRuleFactory.EP_NAME.point.registerExtension(testCustomValidationRuleFactory, testRootDisposable)
    doFieldTest(EventFields.StringValidatedByCustomRule("class", TestCustomValidationRule::class.java), hashSetOf("{util#custom_rule_factory}"))
  }

  fun `test generate string field validated by list of possible values`() {
    doFieldTest(EventFields.String("class", listOf("foo", "bar")), hashSetOf("{enum:foo|bar}"))
  }

  fun `test generate enum field`() {
    doFieldTest(EventFields.Enum("enum", TestEnum::class.java), hashSetOf("{enum:FOO|BAR}"))
  }

  fun `test generate string list validated by custom rule`() {
    val customValidationRule = TestCustomValidationRule("index_id")
    CustomValidationRule.EP_NAME.point.registerExtension(customValidationRule, testRootDisposable)
    doFieldTest(EventFields.StringListValidatedByCustomRule("fields", TestCustomValidationRule::class.java), hashSetOf("{util#index_id}"))
  }

  fun `test generate string list validated by regexp`() {
    doFieldTest(EventFields.StringListValidatedByRegexp("fields", "index_id"), hashSetOf("{regexp#index_id}"))
  }

  fun `test generate string list validated by enum`() {
    doFieldTest(EventFields.StringListValidatedByEnum("fields", "index_id"), hashSetOf("{enum#index_id}"))
  }

  fun `test generate string list validated by list of possible values`() {
    doFieldTest(EventFields.StringList("fields", listOf("foo", "bar")), hashSetOf("{enum:foo|bar}"))
  }

  fun `test generate string validated by inline regexp`() {
    doFieldTest(EventFields.StringValidatedByInlineRegexp("id", "\\d+.\\d+"), hashSetOf("{regexp:\\d+.\\d+}"))
  }

  fun `test generate string list validated by inline regexp`() {
    doFieldTest(EventFields.StringListValidatedByInlineRegexp("fields", "\\d+.\\d+"), hashSetOf("{regexp:\\d+.\\d+}"))
  }

  fun `test generate plugin info with class name`() {
    val expectedValues = hashSetOf(FieldDescriptor("quickfix_name", hashSetOf("{util#class_name}"))) + pluginInfoFields
    doCompositeFieldTest(EventFields.Class("quickfix_name"), expectedValues)
  }

  fun `test generate plugin section`() {
    val descriptors = buildEventsScheme(null)
    assertTrue(descriptors.any { x -> x.plugin.id == "com.intellij" })
  }

  fun `test generate fileName`() {
    val group = buildGroupDescription()
    assertEquals("EventSchemeBuilderTest.kt", group.fileName)
  }

  /**
   * Tests the generation of descriptions for event log groups and events that are not registered
   * in [RegisteredLogDescriptionsProcessor].
   *
   * This method assesses the following behaviors.
   * Ensures group and event descriptions are not set when [RegisteredLogDescriptionsProcessor.isRegistered] is false.
   * Verifies that field-level descriptions remain intact even when the group or event descriptions
   * are not registered.
   */
  fun `test generate not registered descriptions`() {
    val groupDescription = "Test group description"
    val eventDescription = "Description of test event"
    val fieldDescription = "Number of elements in event"
    val eventLogGroup = EventLogGroup("test.group.id", 1, "FUS", groupDescription)
    eventLogGroup.registerEvent("test_event", EventFields.Int("count", fieldDescription), eventDescription)
    val collector = EventsSchemeBuilder.FeatureUsageCollectorInfo(TestCounterCollector(eventLogGroup), PluginSchemeDescriptor("testPlugin"))
    val groups = EventsSchemeBuilder.collectGroupsFromExtensions("count", listOf(collector), "FUS")

    val groupDescriptor = groups.first()
    assertEquals(null, groupDescriptor.description)
    val eventDescriptor = groupDescriptor.schema.first()
    assertEquals(null, eventDescriptor.description)
    assertEquals(fieldDescription, eventDescriptor.fields.first().description)
  }

  /**
   * Tests the generation of registered descriptions for event log groups and their associated events and fields.
   *
   * The method verifies that descriptions for event log groups, events are correctly generated and stored
   * when [RegisteredLogDescriptionsProcessor.isRegistered] is true.
   */
  fun `test generate registered descriptions1`() {
    RegisteredLogDescriptionsProcessor.reset(true)
    val groupDescription = "Test group description5"
    val eventDescription = "Description of test event"
    val fieldDescription = "Number of elements in event"
    val eventLogGroup = EventLogGroup("test.group.id", 1, "FUS", groupDescription)
    eventLogGroup.registerEvent("test_event", EventFields.Int("count", fieldDescription), eventDescription)
    val collector = EventsSchemeBuilder.FeatureUsageCollectorInfo(TestCounterCollector(eventLogGroup), PluginSchemeDescriptor("testPlugin"))
    val groups = EventsSchemeBuilder.collectGroupsFromExtensions("count", listOf(collector), "FUS")

    val groupDescriptor = groups.first()
    assertEquals(groupDescription, groupDescriptor.description)
    val eventDescriptor = groupDescriptor.schema.first()
    assertEquals(eventDescription, eventDescriptor.description)
    assertEquals(fieldDescription, eventDescriptor.fields.first().description)
  }

  /**
   * Tests the behavior of generating and registering event descriptions for event log groups with identical identifiers
   * but differing descriptions.
   * Verifies that the error message contains the expected details when an attempt is made to override an already
   * registered group description.
   */
  fun `test generate registered descriptions for the same groups with different descriptions`() {
    RegisteredLogDescriptionsProcessor.reset(true)
    val groupDescription = "Test group description"
    val eventDescription = "Description of test event"
    val fieldDescription = "Number of elements in event"
    val eventLogGroup1 = EventLogGroup("test.group.id", 1, "FUS", groupDescription)
    eventLogGroup1.registerEvent("test_event", EventFields.Int("count", fieldDescription), eventDescription)
    try {
      EventLogGroup("test.group.id", 1, "FUS", "new descriptions")
      fail("Expected IllegalStateException")
    }
    catch (e: IllegalStateException) {
      assertEquals(
        "Trying to override registered event log group description in group 'test.group.id'. Old description: Test group description, new description: new descriptions.",
        e.message
      )
    }
  }

  /**
   * Tests the behavior of generating and registering event descriptions with identical event identifiers
   * within the same event group but with differing descriptions.
   * Validates the error message when attempting to overwrite an existing event description.
   */
  fun `test generate registered descriptions for the same events with different descriptions`() {
    RegisteredLogDescriptionsProcessor.reset(true)
    val groupDescription = "Test group description"
    val eventDescription = "Description of test event"
    val fieldDescription = "Number of elements in event"
    val eventLogGroup1 = EventLogGroup("test.group.id", 1, "FUS", groupDescription)
    eventLogGroup1.registerEvent("test_event", EventFields.Int("count", fieldDescription), eventDescription)

    assertThrows(IllegalStateException::class.java, "Trying to override registered event log description for event 'test_event' in group 'test.group.id'. Old description: Description of test event, new description: new description.") {
      eventLogGroup1.registerEvent("test_event", EventFields.Int("count", fieldDescription), "new description")
    }
  }

  /**
   * Test that the object array property ("parent.middle") of the event is present
   * in case there are object lists in the path to the field "parent.middle.child.count"
   */
  fun `test object arrays fields`() {
    val eventLogGroup = EventLogGroup("test.group.id", 1, "FUS", "test group")
    eventLogGroup.registerEvent(
      "event.id",
      ObjectEventField("parent", ObjectListEventField("middle", ObjectEventField("child",
                                                                                 EventFields.Int("count")))),
      "event description"
    )
    val collector = EventsSchemeBuilder.FeatureUsageCollectorInfo(TestCounterCollector(eventLogGroup), PluginSchemeDescriptor("testPlugin"))
    val groups = EventsSchemeBuilder.collectGroupsFromExtensions("count", listOf(collector), "FUS")

    val event = groups.first().schema.first().objectArrays
    assertNotNull("Object arrays should be not null", event)
    assertEquals(1, event!!.size)
    assertEquals("parent.middle", event.first())
  }

  fun `test group data is added to events`() {
    val testField = EventFields.String("test_field", listOf("test_value_1", "test_value_2"))
    val groupDataEntry = Pair<EventField<*>, FeatureUsageData.() -> Unit>(
      testField as EventField<*>
    ) { fuData: FeatureUsageData -> fuData.addData("test_field", "test_value1") }
    val eventLogGroup = EventLogGroup("test.group.id", 1, "FUS", "test group", listOf(groupDataEntry))
    eventLogGroup.registerEvent("test_event1", "event1 description")
    eventLogGroup.registerEvent("test_event2", "event2 description")

    val collector = EventsSchemeBuilder.FeatureUsageCollectorInfo(TestCounterCollector(eventLogGroup), PluginSchemeDescriptor("testPlugin"))
    val groups = EventsSchemeBuilder.collectGroupsFromExtensions("count", listOf(collector), "FUS")

    assertEquals(2, groups.first().schema.size)

    val event1 = groups.first().schema.first()
    assertEquals(1, event1.fields.size)
    assertEquals("test_field", event1.fields.first().path)
    assertTrue(event1.fields.first().value.contains("{enum:test_value_1|test_value_2}"))

    val event2 = groups.first().schema.last()
    assertEquals(1, event2.fields.size)
    assertEquals("test_field", event2.fields.first().path)
    assertTrue(event2.fields.first().value.contains("{enum:test_value_1|test_value_2}"))
  }

  private fun doFieldTest(eventField: EventField<*>, expectedValues: Set<String>) {
    val group = buildGroupDescription(eventField)
    val event = group.schema.first()
    assertSameElements(event.fields.first().value, expectedValues)
  }

  private fun doCompositeFieldTest(eventField: EventField<*>, expectedValues: Set<FieldDescriptor>) {
    val group = buildGroupDescription(eventField)
    val event = group.schema.first()
    assertSameElements(event.fields, expectedValues)
  }

  private fun buildGroupDescription(eventField: EventField<*>? = null): GroupDescriptor {
    val eventLogGroup = EventLogGroup("test.group.id", 1, "FUS", "group description")
    if (eventField != null) {
      eventLogGroup.registerEvent("test_event", eventField, "event description")
    }
    val collector = EventsSchemeBuilder.FeatureUsageCollectorInfo(TestCounterCollector(eventLogGroup), PluginSchemeDescriptor("testPlugin"))
    val groups = EventsSchemeBuilder.collectGroupsFromExtensions("count", listOf(collector), "FUS")
    assertSize(1, groups)
    return groups.first()
  }

  enum class TestEnum { FOO, BAR }

  class TestCounterCollector(private val eventLogGroup: EventLogGroup) : CounterUsagesCollector() {
    init {
      forceCalculateFileName()
    }
    override fun getGroup(): EventLogGroup = eventLogGroup
  }

  class TestCustomValidationRuleFactory(private val ruleId: String) : CustomValidationRuleFactory {
    override fun createValidator(contextData: EventGroupContextData): TestCustomValidationRule {
      return TestCustomValidationRule(ruleId)
    }

    override fun getRuleId(): String {
      return ruleId
    }

    override fun getRuleClass(): Class<*> {
      return TestCustomValidationRule::class.java
    }
  }

  class TestCustomValidationRule(private val ruleId: String) : CustomValidationRule() {
    override fun getRuleId(): String = ruleId

    override fun doValidate(data: String, context: EventContext): ValidationResultType {
      return ValidationResultType.ACCEPTED
    }
  }
}