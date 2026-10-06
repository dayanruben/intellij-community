// KTIJ-33359: the implementation dereferences the parameter, so the interface parameter is not null too
public class OverrideNonNullContainer {
    interface OverrideNonNullFace {
        void subjectMethod(Long id);
    }

    public static class OverrideNonNullImpl implements OverrideNonNullFace {
        @Override
        public void subjectMethod(Long id) {
            byte ping = id.byteValue();
        }
    }
}
