// CHOSEN_OPTION: Add use-site target 'property'
// LANGUAGE_VERSION: 2.0

@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
internal annotation class Anno

class MyClass(<caret>@Anno val foo: String)
