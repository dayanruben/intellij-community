package test;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

// KTIJ-26127: in a @NullMarked class, an unannotated type and its type arguments are not null
@NullMarked
public class Main {
    private final List<String> names = new ArrayList<>();

    public List<String> names() {
        return names;
    }

    public @Nullable String first() {
        return names.isEmpty() ? null : names.get(0);
    }

    public void add(String name) {
        names.add(name);
    }
}
