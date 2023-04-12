import java.lang.invoke.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.function.*;

public class CRaCPhase {

	private static final SwitchPoint beforePoint = new SwitchPoint();
	private static final SwitchPoint afterPoint = new SwitchPoint();

	/**
	 * Switchpoint guards the transition between normal mode and pre-checkpoint mode.
	 */
	public static MethodHandle beforeGuard(Function normal, Function preCheckpoint) {
		try {
			MethodHandle apply = MethodHandles.lookup().findVirtual(Function.class, "apply", MethodType.methodType(Object.class, Object.class));
			
			MethodHandle normalMH = apply.bindTo(normal);
			MethodHandle preMH = apply.bindTo(preCheckpoint);

			return beforePoint.guardWithTest(normalMH, preMH);
		} catch(Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static MethodHandle aroundGuard(Function normal, Function checkpoint) {
		try {
			MethodHandle apply = MethodHandles.lookup().findVirtual(Function.class, "apply", MethodType.methodType(Object.class, Object.class));
			
			MethodHandle normalMH = apply.bindTo(normal);
			MethodHandle preMH = apply.bindTo(checkpoint);

			/*
				if (!beforeSwitchPoint.invalidate()) {
					normal.call();
                                } else {
					if (!afterSwtichPoint.invalidate()) {
						checkpoint.call();
					} else { 
						normal.call();
					}
				}
			*/
			MethodHandle afterTransition = afterPoint.guardWithTest(preMH,normalMH);
			MethodHandle beforeTransition = beforePoint.guardWithTest(normalMH, afterTransition);
			
			return beforeTransition;	
	
		} catch(Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static volatile boolean STOP = false;

	public static void sleep(int time) {
		try {
			Thread.sleep(time);
		} catch(Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static void main(String[] args) throws Throwable{
	
		// create some threads calling Test::getSpecialValue()
		for (int i = 0; i < 5; i++) {
			new Thread(
				() -> { 
					while(!STOP) {
						sleep(100);
						Object o = Test.getSpecialValue();
						System.out.println(Thread.currentThread().getName() + "::" + o.toString());
					}
				},
				"Thread("+i+")"
			).start();
		}

		Thread.sleep(400);
		// go for the checkpoint
		//beforePoint.invalidate();
		SwitchPoint.invalidateAll(new SwitchPoint[]{beforePoint});
		System.out.println("==== Before Invalidated ===");
		Core.beforeNeedfull();
		System.out.println("==== Before Checkpoint called  ===");
		
		// restore and see the updated value
		// TODO

		Core.afterNeedfull();
		System.out.println("==== Restore Checkpoint called  ===");
		//afterPoint.invalidate();
		SwitchPoint.invalidateAll(new SwitchPoint[]{afterPoint});
		System.out.println("==== Afterpoint Invalidated ===");

		Thread.sleep(300);
		STOP = true;
		Thread.sleep(300);
	}

		
}

class Test implements Resource {

	private static volatile Object SPECIAL_VALUE = "initialValue";
	private static Lock LOCK = new ReentrantLock();

	//private static MethodHandle GETTER = CRaCPhase.beforeGuard(v -> getSpecialValueRaw(), v2 -> getSpecialValueLocked());

	private static MethodHandle GETTER = CRaCPhase.aroundGuard(v -> getSpecialValueRaw(), v2 -> getSpecialValueLocked());

	public static Object getSpecialValue() {
		try {
			// passing null as the object as Function requries an input
			return GETTER.invokeExact((Object)null);
		} catch(Throwable e) {
			throw new RuntimeException(e);
		}
	}

	private static Object getSpecialValueRaw() {
		System.out.print("...RAW...");
		return SPECIAL_VALUE;
	}


	private static Object getSpecialValueLocked() {
		LOCK.lock();
		try {
			
			System.out.print("...LOCKED...");
			return SPECIAL_VALUE;
		} finally {
			LOCK.unlock();
		}
	}

	static {
		Core.getGlobalContext().register(new Test());
	}

	public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
		// prevent access to the resource
		LOCK.lock();
	}


	public void afterRestore(Context<? extends Resource> context) throws Exception {
		// update value to something reflecting the current system
		SPECIAL_VALUE = "restoredValue";
		LOCK.unlock();
	}
	   

}	

class Core {
	public static Core GLOBAL = new Core();
	static List<Resource> resources = new ArrayList<>();

	public static Core getGlobalContext() { return GLOBAL; }

 	public void register(Resource o) { resources.add(o);} 

	public static void beforeNeedfull() throws Throwable{
		for (Resource r : resources) {
			r.beforeCheckpoint(null);
		}
	}

	public static void afterNeedfull() throws Throwable{
		for (Resource r : resources) {
			r.afterRestore(null);
		}
	}

}


class Context<R extends Resource> {
 static void foo() {}
}

interface Resource {
	
	public void beforeCheckpoint(Context<? extends Resource> context) throws Exception;

	public void afterRestore(Context<? extends Resource> context) throws Exception;

}

