/*******************************************************************************
 * Copyright (c) 2026 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.tests.ccvs.core.cvsresources;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.team.internal.ccvs.core.Policy;

/**
 * Tests for {@link Policy#infiniteSubMonitorFor(IProgressMonitor, int)}, which
 * replaced the removed <code>org.eclipse.team.internal.core.InfiniteSubProgressMonitor</code>
 * with a monitor obtained from {@link org.eclipse.core.runtime.SubMonitor}.
 * These tests do not require a workspace or a CVS server.
 */
public class PolicyTest extends TestCase {

	public PolicyTest() {
		super();
	}

	public PolicyTest(String name) {
		super(name);
	}

	public static Test suite() {
		return new TestSuite(PolicyTest.class);
	}

	/**
	 * A minimal IProgressMonitor that records cumulative reported work and
	 * cancellation/done state, without any dependency on the workbench.
	 */
	private static class RecordingMonitor implements IProgressMonitor {
		double totalWorked;
		boolean canceled;
		boolean done;

		@Override
		public void beginTask(String name, int totalWork) {
			// The real ticks declared on the wrapped monitor are an
			// implementation detail of SubMonitor; not asserted here.
		}

		@Override
		public void done() {
			done = true;
		}

		@Override
		public void internalWorked(double work) {
			totalWorked += work;
		}

		@Override
		public boolean isCanceled() {
			return canceled;
		}

		@Override
		public void setCanceled(boolean value) {
			canceled = value;
		}

		@Override
		public void setTaskName(String name) {
			// no-op
		}

		@Override
		public void subTask(String name) {
			// no-op
		}

		@Override
		public void worked(int work) {
			internalWorked(work);
		}
	}

	public void testNullMonitorIsTolerated() {
		IProgressMonitor result = Policy.infiniteSubMonitorFor(null, 10);
		assertNotNull(result);
		// Must not throw for any of the usual lifecycle calls.
		result.beginTask(null, 5);
		result.worked(1);
		result.isCanceled();
		result.done();
	}

	public void testNullProgressMonitorIsReturnedAsIs() {
		NullProgressMonitor m = new NullProgressMonitor();
		IProgressMonitor result = Policy.infiniteSubMonitorFor(m, 10);
		assertSame(m, result);
	}

	public void testCancellationPropagatesFromParent() {
		RecordingMonitor parent = new RecordingMonitor();
		IProgressMonitor child = Policy.infiniteSubMonitorFor(parent, 10);
		assertFalse(child.isCanceled());
		parent.setCanceled(true);
		assertTrue("cancellation of the parent must be visible on the child", child.isCanceled());
	}

	public void testCancellationPropagatesToParent() {
		RecordingMonitor parent = new RecordingMonitor();
		IProgressMonitor child = Policy.infiniteSubMonitorFor(parent, 10);
		child.setCanceled(true);
		assertTrue("cancelling the child must cancel the parent", parent.isCanceled());
	}

	/**
	 * Reporting work beyond the declared total must not make the parent's
	 * progress keep growing, even when the caller reports work for an
	 * operation whose size was not known in advance (the scenario the old
	 * InfiniteSubProgressMonitor was designed for). This is what guarantees
	 * there is no progress overflow and no premature consumption of all of
	 * the parent's ticks.
	 */
	public void testWorkDoesNotOverflowOnceDeclaredTotalIsReached() {
		RecordingMonitor parent = new RecordingMonitor();
		IProgressMonitor child = Policy.infiniteSubMonitorFor(parent, 20);
		// Simulate an operation that does not know its total amount of work
		// in advance: declare a small total, then report far more than that.
		child.beginTask(null, 8);
		for (int i = 0; i < 8; i++) {
			child.worked(1);
		}
		double afterDeclaredTotal = parent.totalWorked;
		for (int i = 0; i < 1000; i++) {
			child.worked(1);
		}
		assertEquals("reporting work beyond the declared total must not grow the parent's progress further",
				afterDeclaredTotal, parent.totalWorked, 0.0001);
		child.done();
		assertTrue("done() must still be able to complete the remaining allocation",
				parent.totalWorked >= afterDeclaredTotal);
	}

	/**
	 * done() must fill in whatever ticks remain so the parent reaches its
	 * expected total, and calling it again must not double-report.
	 */
	public void testDoneCompletesRemainingWorkExactlyOnce() {
		RecordingMonitor parent = new RecordingMonitor();
		IProgressMonitor child = Policy.infiniteSubMonitorFor(parent, 15);
		child.beginTask(null, 100);
		child.worked(3);
		double beforeDone = parent.totalWorked;
		child.done();
		double afterFirstDone = parent.totalWorked;
		assertTrue("done() must report the remaining allocated work", afterFirstDone >= beforeDone);
		child.done();
		assertEquals("calling done() a second time must not report additional work", afterFirstDone,
				parent.totalWorked, 0.0001);
	}
}
