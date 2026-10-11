import java.util.ArrayList;
import java.util.List;

class Names {
    private final List<String> names = new ArrayList<>();

    void add() {
        names.add("a");
    }

    String first() {
        return names.get(0);
    }
}
