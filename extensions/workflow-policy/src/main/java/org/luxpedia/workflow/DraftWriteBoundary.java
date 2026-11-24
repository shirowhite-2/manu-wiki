package org.luxpedia.workflow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import jakarta.servlet.*;

/** Keep the document guard until the store has committed, including exceptional requests.
 * Intended for this deployment's single XWiki node; clustered deployments need a distributed guard.
 */
public final class DraftWriteBoundary implements ServletContainerInitializer, Filter {
    private static final ReentrantLock[] LOCKS = new ReentrantLock[257];
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    static { for (int i = 0; i < LOCKS.length; i++) { LOCKS[i] = new ReentrantLock(); } }
    @Override public void onStartup(Set<Class<?>> classes, ServletContext context) {
        if (context.getFilterRegistration("luxpedia-draft-write-boundary") != null) { return; }
        var filter = context.addFilter("luxpedia-draft-write-boundary", this);
        filter.setAsyncSupported(true);
        filter.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD), false, "/*");
    }
    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        if (CURRENT.get() != null) { chain.doFilter(request, response); return; }
        try (Scope ignored = open()) { chain.doFilter(request, response); }
    }
    static boolean present() { return CURRENT.get() != null; }
    static Scope open() {
        if (CURRENT.get() != null) { throw new IllegalStateException("Nested write boundary"); }
        Scope scope = new Scope(); CURRENT.set(scope); return scope;
    }
    static void guard(String name) throws InterruptedException {
        Scope scope = CURRENT.get();
        WorkflowPolicyListener.require(scope != null, "Draft write boundary is unavailable; restart XWiki with the current policy.");
        var lock = LOCKS[Math.floorMod(name.hashCode(), LOCKS.length)];
        if (scope.held.contains(lock)) { return; }
        WorkflowPolicyListener.require(lock.tryLock(10, TimeUnit.SECONDS), "Another draft operation is finishing. Please retry.");
        scope.held.add(lock);
    }
    static final class Scope implements AutoCloseable {
        final ArrayList<ReentrantLock> held = new ArrayList<>();
        @Override public void close() {
            for (int i = held.size() - 1; i >= 0; i--) { held.get(i).unlock(); }
            CURRENT.remove();
        }
    }
}
