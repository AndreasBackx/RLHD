package rs117.hd.overlays;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
@Singleton
public class FrameTimer {
	public static final int CPU_TIMER = 0;
	public static final int ASYNC_CPU_TIMER = 1;
	public static final int GPU_TIMER = 2;
	public static final int ASYNC_GPU_TIMER = 3;

	private static final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	private static final int NUM_TIMERS = Timer.TIMERS.length;
	private static final int NUM_GPU_TIMERS = (int) Arrays.stream(Timer.TIMERS).filter(Timer::isGpuTimer).count();
	private static final int NUM_GPU_DEBUG_GROUPS = (int) Arrays.stream(Timer.TIMERS).filter(Timer::hasGpuDebugGroup).count();
	private static final int INITIAL_QUERY_FRAMES = 3;

	private final AutoTimer[] autoTimers = new AutoTimer[NUM_TIMERS];
	private final boolean[] activeTimers = new boolean[NUM_TIMERS];
	private final long[] timings = new long[NUM_TIMERS];
	private final ArrayDeque<Timer> glDebugGroupStack = new ArrayDeque<>(NUM_GPU_DEBUG_GROUPS);
	private final ArrayDeque<Listener> listeners = new ArrayDeque<>();
	private final ArrayDeque<QueryFrame> pendingFrames = new ArrayDeque<>();
	private final ArrayDeque<QueryFrame> freeFrames = new ArrayDeque<>();
	private final List<QueryFrame> queryFrames = new ArrayList<>();
	private QueryFrame currentFrame;
	private long[] lastGCTimes;

	private static class QueryFrame {
		private final int[] queries = new int[NUM_TIMERS * 2];
		private final boolean[] gpuUsed = new boolean[NUM_TIMERS];
		private final boolean[] gpuEnded = new boolean[NUM_TIMERS];
		private int completionQuery;
		private FrameTimings timings;
		private Listener[] listeners;

		private void reset() {
			Arrays.fill(gpuUsed, false);
			Arrays.fill(gpuEnded, false);
			timings = null;
			listeners = null;
		}
	}

	@RequiredArgsConstructor
	public class AutoTimer implements AutoCloseable {
		private final Timer timer;

		@Override
		public void close() {
			end(timer);
		}
	}

	@SuppressWarnings("resource")
	public FrameTimer() {
		for (int i = 0; i < NUM_TIMERS; i++)
			autoTimers[i] = new AutoTimer(Timer.TIMERS[i]);
	}

	@Getter
	private boolean isActive = false;

	public long cumulativeError;
	public long errorCompensation;

	private void initialize() {
		clientThread.invoke(() -> {
			for (int i = 0; i < INITIAL_QUERY_FRAMES; i++)
				freeFrames.add(createQueryFrame());
			currentFrame = freeFrames.removeFirst();

			isActive = true;
			plugin.setupSyncMode();
			plugin.enableDetailedTimers = true;

			// Estimate the timer's own runtime, with a warm-up run first
			final int iterations = 100000;
			final int compensation = 1950000; // additional manual correction
			for (int i = 0; i < 2; i++) {
				errorCompensation = 0;
				for (int j = 0; j < iterations; j++) {
					begin(Timer.DRAW_FRAME);
					end(Timer.DRAW_FRAME);
				}
				errorCompensation = (timings[Timer.DRAW_FRAME.ordinal()] + compensation) / iterations;
				timings[Timer.DRAW_FRAME.ordinal()] = 0;
			}
			log.debug("Estimated the overhead of timers to be around {} ns", errorCompensation);
		});
	}

	private void destroy() {
		clientThread.invoke(() -> {
			if (!isActive)
				return;

			isActive = false;
			plugin.setupSyncMode();
			plugin.enableDetailedTimers = false;

			for (var frame : queryFrames) {
				glDeleteQueries(frame.queries);
				glDeleteQueries(frame.completionQuery);
			}
			queryFrames.clear();
			pendingFrames.clear();
			freeFrames.clear();
			currentFrame = null;
			reset();
		});
	}

	private QueryFrame createQueryFrame() {
		QueryFrame frame = new QueryFrame();
		int[] queryNames = new int[NUM_GPU_TIMERS * 2];
		glGenQueries(queryNames);
		int queryIndex = 0;
		for (var timer : Timer.TIMERS)
			if (timer.isGpuTimer())
				for (int j = 0; j < 2; ++j)
					frame.queries[timer.ordinal() * 2 + j] = queryNames[queryIndex++];
		frame.completionQuery = glGenQueries();
		queryFrames.add(frame);
		return frame;
	}

	@FunctionalInterface
	public interface Listener {
		default void onFrameSubmission(FrameTimings timings) {}

		void onFrameCompletion(FrameTimings timings);
	}

	public void addTimingsListener(Listener listener) {
		if (listeners.isEmpty())
			initialize();
		listeners.add(listener);
	}

	public void removeTimingsListener(Listener listener) {
		listeners.remove(listener);
		if (listeners.isEmpty())
			destroy();
	}

	public void removeAllListeners() {
		listeners.clear();
		destroy();
	}

	public void reset() {
		Arrays.fill(timings, 0);
		Arrays.fill(activeTimers, false);
		if (currentFrame != null)
			currentFrame.reset();
		cumulativeError = 0;
	}

	public AutoTimer begin(Timer timer) {
		int index = timer.ordinal();
		if (log.isDebugEnabled() && timer.hasGpuDebugGroup() && HdPlugin.GL_CAPS.OpenGL43) {
			if (glDebugGroupStack.contains(timer)) {
				log.warn("The debug group {} is already on the stack", timer.name());
			} else {
				glDebugGroupStack.push(timer);
				GL43C.glPushDebugGroup(GL43C.GL_DEBUG_SOURCE_APPLICATION, index, timer.name);
			}
		}

		if (!isActive)
			return null;

		if (timer.isGpuTimer()) {
			if (currentFrame.gpuUsed[index])
				throw new UnsupportedOperationException("Cumulative GPU timing isn't supported");
			glQueryCounter(currentFrame.queries[index * 2], GL_TIMESTAMP);
			currentFrame.gpuUsed[index] = true;
		} else if (!activeTimers[index]) {
			cumulativeError += errorCompensation + 1 >> 1;
			timings[index] -= System.nanoTime() - cumulativeError;
		}
		activeTimers[index] = true;

		return autoTimers[index];
	}

	public void end(Timer timer) {
		if (log.isDebugEnabled() && timer.hasGpuDebugGroup() && HdPlugin.GL_CAPS.OpenGL43) {
			if (glDebugGroupStack.peek() != timer) {
				if (glDebugGroupStack.contains(timer))
					log.warn("The debug group {} was popped out of order", timer.name());
			} else {
				glDebugGroupStack.pop();
				GL43C.glPopDebugGroup();
			}
		}

		if (!isActive || !activeTimers[timer.ordinal()])
			return;

		if (timer.isGpuTimer()) {
			glQueryCounter(currentFrame.queries[timer.ordinal() * 2 + 1], GL_TIMESTAMP);
			currentFrame.gpuEnded[timer.ordinal()] = true;
			// leave the GPU timer active, since it needs to be gathered at a later point
		} else {
			cumulativeError += errorCompensation >> 1;
			timings[timer.ordinal()] += System.nanoTime() - cumulativeError;
			activeTimers[timer.ordinal()] = false;
		}
	}

	public void add(Timer timer, long nanos) {
		if (isActive)
			timings[timer.ordinal()] += nanos;
	}

	public void add(Timer timer, long duration, TimeUnit unit) {
		if (isActive)
			timings[timer.ordinal()] += TimeUnit.NANOSECONDS.convert(duration, unit);
	}

	public void endFrameAndReset() {
		if (HdPlugin.GL_CAPS.OpenGL43) {
			while (!glDebugGroupStack.isEmpty()) {
				log.warn("The debug group {} was never popped", glDebugGroupStack.pop().name());
				GL43C.glPopDebugGroup();
			}
		}

		if (!isActive)
			return;

		long frameEndNanos = System.nanoTime();
		long frameEndTimestamp = System.currentTimeMillis();

		trackGarbageCollection();

		for (var timer : Timer.TIMERS) {
			int i = timer.ordinal();
			if (timer.isGpuTimer()) {
				if (currentFrame.gpuUsed[i] && !currentFrame.gpuEnded[i]) {
					log.warn("Timer {} was never ended", timer);
					glQueryCounter(currentFrame.queries[i * 2 + 1], GL_TIMESTAMP);
					currentFrame.gpuEnded[i] = true;
				}
			} else {
				if (activeTimers[i]) {
					// End the CPU timer automatically, but warn about it
					log.warn("Timer {} was never ended", timer);
					timings[i] += frameEndNanos;
				}
			}
		}

		final float cpuLoad = (float) osBean.getSystemLoadAverage() / osBean.getAvailableProcessors();
		currentFrame.timings = new FrameTimings(frameEndTimestamp, timings, cpuLoad);
		currentFrame.listeners = listeners.toArray(new Listener[0]);
		for (var listener : currentFrame.listeners)
			listener.onFrameSubmission(currentFrame.timings);
		glQueryCounter(currentFrame.completionQuery, GL_TIMESTAMP);
		pendingFrames.addLast(currentFrame);
		currentFrame = null;

		Arrays.fill(timings, 0);
		Arrays.fill(activeTimers, false);
		cumulativeError = 0;
		drainCompletedFrames();
		if (isActive) {
			currentFrame = freeFrames.pollFirst();
			if (currentFrame == null) {
				currentFrame = createQueryFrame();
				log.debug("GPU timing query pool grew to {} frames", queryFrames.size());
			}
			currentFrame.reset();
		}
	}

	private void drainCompletedFrames() {
		while (!pendingFrames.isEmpty()) {
			QueryFrame frame = pendingFrames.peekFirst();
			if (glGetQueryObjecti(frame.completionQuery, GL_QUERY_RESULT_AVAILABLE) == GL_FALSE)
				return;

			pendingFrames.removeFirst();
			for (var timer : Timer.TIMERS) {
				int i = timer.ordinal();
				if (!frame.gpuUsed[i])
					continue;
				long start = glGetQueryObjectui64(frame.queries[i * 2], GL_QUERY_RESULT);
				long end = glGetQueryObjectui64(frame.queries[i * 2 + 1], GL_QUERY_RESULT);
				frame.timings.timers[i] += end - start;
			}

			Listener[] frameListeners = frame.listeners;
			FrameTimings frameTimings = frame.timings;
			frame.reset();
			freeFrames.addLast(frame);
			for (var listener : frameListeners)
				if (listeners.contains(listener))
					listener.onFrameCompletion(frameTimings);
			if (!isActive)
				return;
		}
	}

	private void trackGarbageCollection() {
		List<GarbageCollectorMXBean> garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans();
		if (lastGCTimes == null || lastGCTimes.length != garbageCollectors.size())
			lastGCTimes = new long[garbageCollectors.size()];

		plugin.garbageCollectionCount = 0;
		long elapsedDuration = 0;
		for (int i = 0; i < garbageCollectors.size(); i++) {
			var gc = garbageCollectors.get(i);
			long time = gc.getCollectionTime();
			if (time > 0 && time != lastGCTimes[i]) {
				long duration = time - lastGCTimes[i];
				lastGCTimes[i] = time;
				elapsedDuration += duration;
			}
			plugin.garbageCollectionCount += gc.getCollectionCount();
		}

		add(Timer.GARBAGE_COLLECTION, elapsedDuration * 1_000_000L);
	}
}
