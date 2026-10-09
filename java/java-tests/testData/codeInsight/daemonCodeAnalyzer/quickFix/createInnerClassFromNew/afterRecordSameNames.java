// "Create inner record 'Coordinates'" "true-preview"
public class Test {
    public void main() {
        Coordinates c = new Coordinates(50.08, 14.43);
    }

    private record Coordinates(double v, double v1) {
    }
}
