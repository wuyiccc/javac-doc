package run.test;

public class Outer<T> {


    class Inner<T> {

        public void md(Object o) {
            Object x = (Outer<String>.Inner<String>[])o;
        }
    }

}
