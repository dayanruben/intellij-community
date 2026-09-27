// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package main

import (
	"fmt"
	"os"
	"strings"

	"jetbrains.com/plugin-descriptor-writer/internal/descriptorxml"
	"jetbrains.com/plugin-descriptor-writer/internal/structural"
)

// embeddedProductRequest contains the declared inputs of dev_dist_embedded_product_descriptor.
type embeddedProductRequest struct {
	output           string
	source           string
	descriptors      map[string]string
	descriptorsInJar map[string][]string
	modules          []string
	separateJar      map[string]bool
}

func runEmbeddedProduct(lines []string) int {
	parsed, err := parseEmbeddedProductRequest(lines)
	if err != nil {
		fmt.Fprintf(os.Stderr, "ERROR: %v\n", err)
		return 2
	}
	content, err := resolveEmbeddedProduct(parsed)
	if err != nil {
		fmt.Fprintf(os.Stderr, "ERROR: could not resolve the embedded product descriptor (%s): %v\n", parsed.source, err)
		return 1
	}
	if err := writeOutput(parsed.output, content); err != nil {
		fmt.Fprintf(os.Stderr, "ERROR: %v\n", err)
		return 1
	}
	return 0
}

func resolveEmbeddedProduct(parsed embeddedProductRequest) (string, error) {
	content, err := resolveProductContent(parsed, structural.ContentRequest{
		MainModule:  parsed.source,
		SeparateJar: parsed.separateJar,
		Embeds:      true,
	})
	return content.text, err
}

// productContent is a resolved product descriptor and the descriptor cache that resolved it.
type productContent struct {
	text     string
	cache    *structural.Cache
	resolver *structural.Resolver
}

// resolveProductContent resolves the includes of a product descriptor and embeds its content modules.
//
// The embedded product descriptor and the product descriptor share this body. They differ only in the content request.
func resolveProductContent(parsed embeddedProductRequest, request structural.ContentRequest) (productContent, error) {
	files, err := readSeed(parsed.descriptors)
	if err != nil {
		return productContent{}, err
	}
	cache := structural.NewCache(nil)
	if err := seedFromJars(cache, parsed.descriptorsInJar); err != nil {
		return productContent{}, err
	}
	// Kotlin merges the jar seed over the file seed when a direct request declares both.
	for _, loadPath := range files.LoadPaths() {
		data, _ := files.Get(loadPath)
		cache.PutIfAbsent(loadPath, data)
	}
	resolver := structural.NewResolver([]structural.Scope{{Modules: parsed.modules, Cache: cache}})
	source, err := os.ReadFile(parsed.source)
	if err != nil {
		return productContent{}, err
	}
	element, err := descriptorxml.Read(string(source))
	if err != nil {
		return productContent{}, err
	}
	if err := structural.ResolveIncludes(element, resolver); err != nil {
		return productContent{}, err
	}
	if err := structural.EmbedContentModules(element, request, cache, resolver); err != nil {
		return productContent{}, err
	}
	return productContent{text: descriptorxml.Write(element), cache: cache, resolver: resolver}, nil
}

func parseEmbeddedProductRequest(lines []string) (embeddedProductRequest, error) {
	parsed := newProductContentRequest()
	parsed.separateJar = map[string]bool{}
	if err := requireMode(lines, embeddedProductMode); err != nil {
		return parsed, err
	}
	for _, line := range lines {
		if line == "" || line == embeddedProductMode {
			continue
		}
		option, value, _ := strings.Cut(line, "=")
		handled, err := parseProductContentOption(&parsed, option, value)
		if !handled {
			switch option {
			case "--separate-jar":
				parsed.separateJar[value] = true
			default:
				err = fmt.Errorf("unknown embedded product descriptor option '%s'", option)
			}
		}
		if err != nil {
			return parsed, err
		}
	}
	return parsed, checkProductContentRequest(parsed)
}

func newProductContentRequest() embeddedProductRequest {
	return embeddedProductRequest{
		descriptors:      map[string]string{},
		descriptorsInJar: map[string][]string{},
	}
}

// requireMode fails unless the request selects exactly this mode.
func requireMode(lines []string, want string) error {
	mode, err := selectOperation(lines)
	if err != nil {
		return err
	}
	if mode != want {
		return fmt.Errorf("%s is required", want)
	}
	return nil
}

// parseProductContentOption reads an option that both product descriptor modes accept. It returns false for another
// option.
func parseProductContentOption(parsed *embeddedProductRequest, option string, value string) (bool, error) {
	switch option {
	case "--out":
		parsed.output = value
	case "--source":
		parsed.source = value
	case "--descriptor":
		return true, putDescriptor(parsed.descriptors, value)
	case "--descriptor-in-jar":
		return true, appendDescriptorJar(parsed.descriptorsInJar, value)
	case "--module":
		parsed.modules = append(parsed.modules, value)
	default:
		return false, nil
	}
	return true, nil
}

func checkProductContentRequest(parsed embeddedProductRequest) error {
	if parsed.output == "" {
		return fmt.Errorf("--out is required")
	}
	if parsed.source == "" {
		return fmt.Errorf("--source is required")
	}
	return nil
}
