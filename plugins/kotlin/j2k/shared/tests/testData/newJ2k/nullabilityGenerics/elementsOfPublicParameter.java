import java.util.List;

public class Lists {
    public void fillPublic(List<String> list) {
        list.add("a");
    }

    void fillPackagePrivate(List<String> list) {
        list.add("a");
    }
}
