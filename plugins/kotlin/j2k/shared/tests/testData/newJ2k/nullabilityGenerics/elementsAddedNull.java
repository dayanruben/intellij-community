import java.util.ArrayList;
import java.util.List;

class Cache {
    private final List<String> values = new ArrayList<>();

    void clear() {
        values.add(null);
    }

    String first() {
        return values.get(0);
    }
}
