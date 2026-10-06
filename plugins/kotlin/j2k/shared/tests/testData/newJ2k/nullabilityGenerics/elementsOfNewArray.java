class ArrayFactory {
    String[] make() {
        String[] result = new String[2];
        result[0] = "a";
        return result;
    }

    String first() {
        String[] names = {"a", "b"};
        return names[0];
    }
}
