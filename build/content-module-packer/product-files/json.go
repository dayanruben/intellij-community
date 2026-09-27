package main

import (
	"fmt"
	"strconv"
	"strings"
)

// The JSON of `product-info.json` as kotlinx.serialization writes it with `prettyPrint`, a two-space indent,
// `encodeDefaults = false` and `explicitNulls = false`: fields in declaration order, one array element per line, and no
// field that holds its default value. The object keeps its fields in the order they are added.

type jsonValue interface {
	render(indent string) string
}

type jsonField struct {
	name  string
	value jsonValue
}

type jsonObject struct {
	fields []jsonField
}

func (o *jsonObject) add(name string, value jsonValue) {
	o.fields = append(o.fields, jsonField{name: name, value: value})
}

func (o *jsonObject) string(name string, value string) {
	o.add(name, jsonString(value))
}

// optionalString adds a nullable field whose default is `null`.
func (o *jsonObject) optionalString(name string, value *string) {
	if value != nil {
		o.string(name, *value)
	}
}

func (o *jsonObject) number(name string, value int) {
	o.add(name, jsonNumber(value))
}

// strings adds a list field whose default is the empty list.
func (o *jsonObject) strings(name string, values []string) {
	o.array(name, stringArray(values))
}

func stringArray(values []string) jsonArray {
	elements := make(jsonArray, len(values))
	for index, value := range values {
		elements[index] = jsonString(value)
	}
	return elements
}

// array adds a list field whose default is the empty list.
func (o *jsonObject) array(name string, values []jsonValue) {
	if len(values) > 0 {
		o.add(name, jsonArray(values))
	}
}

func (o jsonObject) render(indent string) string {
	if len(o.fields) == 0 {
		return "{}"
	}
	inner := indent + "  "
	var text strings.Builder
	text.WriteString("{\n")
	for index, field := range o.fields {
		if index > 0 {
			text.WriteString(",\n")
		}
		text.WriteString(inner)
		text.WriteString(quote(field.name))
		text.WriteString(": ")
		text.WriteString(field.value.render(inner))
	}
	text.WriteString("\n")
	text.WriteString(indent)
	text.WriteString("}")
	return text.String()
}

type jsonArray []jsonValue

func (a jsonArray) render(indent string) string {
	if len(a) == 0 {
		return "[]"
	}
	inner := indent + "  "
	var text strings.Builder
	text.WriteString("[\n")
	for index, element := range a {
		if index > 0 {
			text.WriteString(",\n")
		}
		text.WriteString(inner)
		text.WriteString(element.render(inner))
	}
	text.WriteString("\n")
	text.WriteString(indent)
	text.WriteString("]")
	return text.String()
}

type jsonString string

func (s jsonString) render(string) string {
	return quote(string(s))
}

type jsonNumber int

func (n jsonNumber) render(string) string {
	return strconv.Itoa(int(n))
}

// quote escapes a string the way kotlinx.serialization does: the quote, the backslash and the control characters, with
// lowercase hex digits, and nothing else.
func quote(value string) string {
	var text strings.Builder
	text.WriteByte('"')
	for _, char := range value {
		switch char {
		case '"':
			text.WriteString(`\"`)
		case '\\':
			text.WriteString(`\\`)
		case '\t':
			text.WriteString(`\t`)
		case '\b':
			text.WriteString(`\b`)
		case '\n':
			text.WriteString(`\n`)
		case '\r':
			text.WriteString(`\r`)
		case '\f':
			text.WriteString(`\f`)
		default:
			if char < 0x20 {
				fmt.Fprintf(&text, `\u%04x`, char)
			} else {
				text.WriteRune(char)
			}
		}
	}
	text.WriteByte('"')
	return text.String()
}
