import java.util.ArrayList;
import java.util.List;

class Lists {
    private final List<String> withNull = new ArrayList<>();
    private final List<String> clean = new ArrayList<>();

    void fill() {
        withNull.add(null);
        clean.add("a");
        print(withNull);
        print(clean);
    }

    private void print(List<? extends String> items) {
        System.out.println(items.size());
    }

    String fromClean() {
        return clean.get(0);
    }

    String fromWithNull() {
        return withNull.get(0);
    }
}
