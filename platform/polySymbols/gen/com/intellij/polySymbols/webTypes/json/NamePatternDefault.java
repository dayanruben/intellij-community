
package com.intellij.polySymbols.webTypes.json;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "required",
    "unique",
    "repeat",
    "template",
    "or",
    "delegate",
    "deprecated",
    "priority",
    "items"
})
public class NamePatternDefault
    extends NamePatternBase
{

    @JsonProperty("required")
    private Boolean required;
    @JsonProperty("unique")
    private Boolean unique;
    @JsonProperty("repeat")
    private Boolean repeat;
    @JsonProperty("template")
    private List<NamePatternTemplate> template = new ArrayList<NamePatternTemplate>();
    @JsonProperty("or")
    private List<NamePatternTemplate> or = new ArrayList<NamePatternTemplate>();
    /**
     * A reference to an element in Web-Types model.
     * 
     */
    @JsonProperty("delegate")
    @JsonPropertyDescription("A reference to an element in Web-Types model.")
    private Reference delegate;
    /**
     * Specifies whether the symbol is deprecated. Deprecated symbol usage is discouraged, but still supported. Value can be a boolean or a string message with explanation and migration information.
     * 
     */
    @JsonProperty("deprecated")
    @JsonPropertyDescription("Specifies whether the symbol is deprecated. Deprecated symbol usage is discouraged, but still supported. Value can be a boolean or a string message with explanation and migration information.")
    private Deprecated deprecated = null;
    /**
     * The priority of the contribution or the pattern. You can use predefined constants `lowest`(`0.0`), `low`(`1.0`), `normal`(`10.0`), `high`(`50.0`), `highest`(`100.0`), or a custom number. By default the `normal` priority is used.
     * 
     */
    @JsonProperty("priority")
    @JsonPropertyDescription("The priority of the contribution or the pattern. You can use predefined constants `lowest`(`0.0`), `low`(`1.0`), `normal`(`10.0`), `high`(`50.0`), `highest`(`100.0`), or a custom number. By default the `normal` priority is used.")
    private Priority priority;
    /**
     * A reference to an element in Web-Types model.
     * 
     */
    @JsonProperty("items")
    @JsonPropertyDescription("A reference to an element in Web-Types model.")
    private ListReference items;

    @JsonProperty("required")
    public Boolean getRequired() {
        return required;
    }

    @JsonProperty("required")
    public void setRequired(Boolean required) {
        this.required = required;
    }

    @JsonProperty("unique")
    public Boolean getUnique() {
        return unique;
    }

    @JsonProperty("unique")
    public void setUnique(Boolean unique) {
        this.unique = unique;
    }

    @JsonProperty("repeat")
    public Boolean getRepeat() {
        return repeat;
    }

    @JsonProperty("repeat")
    public void setRepeat(Boolean repeat) {
        this.repeat = repeat;
    }

    @JsonProperty("template")
    public List<NamePatternTemplate> getTemplate() {
        return template;
    }

    @JsonProperty("template")
    public void setTemplate(List<NamePatternTemplate> template) {
        this.template = template;
    }

    @JsonProperty("or")
    public List<NamePatternTemplate> getOr() {
        return or;
    }

    @JsonProperty("or")
    public void setOr(List<NamePatternTemplate> or) {
        this.or = or;
    }

    /**
     * A reference to an element in Web-Types model.
     * 
     */
    @JsonProperty("delegate")
    public Reference getDelegate() {
        return delegate;
    }

    /**
     * A reference to an element in Web-Types model.
     * 
     */
    @JsonProperty("delegate")
    public void setDelegate(Reference delegate) {
        this.delegate = delegate;
    }

    /**
     * Specifies whether the symbol is deprecated. Deprecated symbol usage is discouraged, but still supported. Value can be a boolean or a string message with explanation and migration information.
     * 
     */
    @JsonProperty("deprecated")
    public Deprecated getDeprecated() {
        return deprecated;
    }

    /**
     * Specifies whether the symbol is deprecated. Deprecated symbol usage is discouraged, but still supported. Value can be a boolean or a string message with explanation and migration information.
     * 
     */
    @JsonProperty("deprecated")
    public void setDeprecated(Deprecated deprecated) {
        this.deprecated = deprecated;
    }

    /**
     * The priority of the contribution or the pattern. You can use predefined constants `lowest`(`0.0`), `low`(`1.0`), `normal`(`10.0`), `high`(`50.0`), `highest`(`100.0`), or a custom number. By default the `normal` priority is used.
     * 
     */
    @JsonProperty("priority")
    public Priority getPriority() {
        return priority;
    }

    /**
     * The priority of the contribution or the pattern. You can use predefined constants `lowest`(`0.0`), `low`(`1.0`), `normal`(`10.0`), `high`(`50.0`), `highest`(`100.0`), or a custom number. By default the `normal` priority is used.
     * 
     */
    @JsonProperty("priority")
    public void setPriority(Priority priority) {
        this.priority = priority;
    }

    /**
     * A reference to an element in Web-Types model.
     * 
     */
    @JsonProperty("items")
    public ListReference getItems() {
        return items;
    }

    /**
     * A reference to an element in Web-Types model.
     * 
     */
    @JsonProperty("items")
    public void setItems(ListReference items) {
        this.items = items;
    }

}
