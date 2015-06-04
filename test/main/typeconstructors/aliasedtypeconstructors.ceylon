alias AdditionLikeOperation
        => <Number> given Number satisfies Summable<Number>
        => Number(Number,Number);

AdditionLikeOperation add = plus;
AdditionLikeOperation<Integer> addInts = plus<Integer>;
AdditionLikeOperation<Float> addFloats = plus<Float>;  

<Number> given Number satisfies Summable<Number>
        => Number(Number,Number) addRef = add;

void doSomething(AdditionLikeOperation addOp) {}

void testDoSomething() {
    doSomething(plus);
    Float z = add(1.0,2.0);
    Integer k = add(1,2);
    addFloats(2.0, 3.0);
    addInts(2, 3);
}

Map<String,String> theMap = nothing;
alias YourMapFunction => Map;
YourMapFunction<String,String> yourMap = theMap;
alias MyMapFunction => <U,V> => Map;
MyMapFunction<String,String> myMap = theMap;

alias CurriedMapTypeFunction
        =>  <Key> given Key satisfies Object
        =>  <<Item> => Map<Key, Item>>;
Map<String,Integer> strIntMap = nothing;
alias StringKeyedMap => CurriedMapTypeFunction<String>;
StringKeyedMap<Integer> strIntMapRef = strIntMap; 

interface Second<Box> given Box<T> {}
interface Boxy<T> {}
alias BoxRef1 => Boxy;
alias BoxRef2 => <T> => Boxy<T>;
Second<BoxRef1> second = nothing;
Second<BoxRef2> third = nothing;

alias Union => <T> => List<T>|Boxy<T>;
Union<String> union = nothing;
List<String>|Boxy<String> verbose = union;