import java.util.ArrayList;
import java.util.List;

class Names {
    private final List<String> names = new ArrayList<String>();

    void add() {
        names.add("a");
    }

    String first() {
        return names.get(0);
    }
}
