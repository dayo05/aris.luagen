package me.ddayo.aris.luagen;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;


// Writing this with Java because of https://stackoverflow.com/questions/79340758/why-inheriting-phantomreference-prevents-object-be-cleaned
public class ArisPhantomReference extends PhantomReference<Object> {
    private final LuaEngine.LuaTask task;
    private final int ref;

    /**
     * Creates a new phantom reference that refers to the given object and
     * is registered with the given queue.
     *
     * <p> It is possible to create a phantom reference with a {@code null}
     * queue.  Such a reference will never be enqueued.
     *
     * @param referent the object the new phantom reference will refer to
     * @param q        the queue with which the reference is to be registered,
     *                 or {@code null} if registration is not required
     */
    public ArisPhantomReference(Object referent, LuaEngine.LuaTask task, int ref, ReferenceQueue<? super Object> q) {
        super(referent, q);
        this.task = task;
        this.ref = ref;
    }

    public LuaEngine.LuaTask getTask() {
        return task;
    }

    public int getRef() {
        return ref;
    }
}
