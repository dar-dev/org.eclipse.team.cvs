/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse CVS Client.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.team.tests.ccvs.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.team.core.TeamException;
import org.eclipse.team.core.variants.ResourceVariantByteStore;
import org.eclipse.team.internal.ccvs.core.streams.CRLFtoLFInputStream;
import org.eclipse.team.internal.ccvs.core.streams.LFtoCRLFInputStream;
import org.eclipse.team.internal.ccvs.core.streams.PollingInputStream;
import org.eclipse.team.internal.ccvs.core.streams.PollingOutputStream;
import org.eclipse.team.internal.ccvs.core.streams.SizeConstrainedInputStream;
import org.eclipse.team.internal.ccvs.core.streams.TimeoutInputStream;
import org.eclipse.team.internal.ccvs.core.streams.TimeoutOutputStream;
import org.eclipse.team.internal.ccvs.core.syncinfo.DescendantResourceVariantByteStore;

import junit.framework.TestCase;

/** Standalone regression tests for helpers removed from Eclipse Team in 4.42. */
public class CompatibilityTests extends TestCase {
	private final IResource file = resource("file");
	private final MemoryStore base = new MemoryStore();
	private final MemoryStore remote = new MemoryStore();
	private final DescendantResourceVariantByteStore store = new DescendantResourceVariantByteStore(base, remote) {
		@Override
		public boolean isVariantKnown(IResource resource) {
			return remote.known.contains(resource);
		}

		@Override
		protected boolean isDescendant(IResource resource, byte[] baseBytes, byte[] remoteBytes) {
			return remoteBytes[0] > baseBytes[0];
		}
	};

	public void testUnfetchedRemoteFallsBackToBase() throws Exception {
		base.setBytes(file, new byte[] { 2 });
		assertSame(base.getBytes(file), store.getBytes(file));
	}

	public void testRemoteWithoutBaseAndLaterRemote() throws Exception {
		remote.setBytes(file, new byte[] { 3 });
		assertSame(remote.getBytes(file), store.getBytes(file));
		base.setBytes(file, new byte[] { 2 });
		assertSame(remote.getBytes(file), store.getBytes(file));
	}

	public void testStaleRemoteFallsBackToBase() throws Exception {
		base.setBytes(file, new byte[] { 3 });
		remote.setBytes(file, new byte[] { 2 });
		assertSame(base.getBytes(file), store.getBytes(file));
	}

	public void testKnownRemoteDeletionDoesNotFallBack() throws Exception {
		base.setBytes(file, new byte[] { 2 });
		store.deleteBytes(file);
		assertNull(store.getBytes(file));
	}

	public void testEqualBytesRemoveRedundantRemoteCache() throws Exception {
		base.setBytes(file, new byte[] { 2 });
		remote.setBytes(file, new byte[] { 3 });
		assertTrue(store.setBytes(file, new byte[] { 2 }));
		assertNull(remote.getBytes(file));
		assertFalse(remote.known.contains(file));
		assertSame(base.getBytes(file), store.getBytes(file));
	}

	public void testMembersExcludeKnownDeletionsAndIncludeRemoteAdditions() throws Exception {
		IResource deleted = resource("deleted");
		IResource added = resource("added");
		base.setBytes(file, new byte[] { 1 });
		base.setBytes(deleted, new byte[] { 1 });
		remote.deleteBytes(deleted);
		remote.setBytes(added, new byte[] { 1 });
		assertEquals(Set.of(file, added), new HashSet<>(Arrays.asList(store.members(file))));
	}

	public void testFlushRunAndDisposeDelegateOnlyToRemote() throws Exception {
		base.setBytes(file, new byte[] { 1 });
		remote.setBytes(file, new byte[] { 2 });
		assertTrue(store.flushBytes(file, IResource.DEPTH_INFINITE));
		assertEquals(IResource.DEPTH_INFINITE, remote.depth);
		assertNotNull(base.getBytes(file));
		store.run(file, monitor -> {}, new NullProgressMonitor());
		assertTrue(remote.ran);
		assertFalse(base.ran);
		store.dispose();
		assertTrue(remote.disposed);
		assertFalse(base.disposed);
	}

	public void testLineEndingConversions() throws Exception {
		try (InputStream in = new CRLFtoLFInputStream(bytes("a\r\nb\nc"))) {
			assertEquals("a\nb\nc", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		try (InputStream in = new LFtoCRLFInputStream(bytes("a\nb\nc"))) {
			assertEquals("a\r\nb\r\nc", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	public void testSizeConstraintDoesNotConsumeFollowingProtocolBytes() throws Exception {
		ByteArrayInputStream underlying = bytes("abcdef");
		try (InputStream in = new SizeConstrainedInputStream(underlying, 3, true)) {
			assertEquals('a', in.read());
		}
		assertEquals('d', underlying.read());
	}

	public void testPollingInputRetriesAndReportsTimeout() throws Exception {
		InputStream underlying = new InputStream() {
			private int attempts;
			@Override
			public int read() throws InterruptedIOException {
				if (++attempts == 1) throw new InterruptedIOException();
				return 'a';
			}
		};
		assertEquals('a', new PollingInputStream(underlying, 2, new NullProgressMonitor()).read());
		InputStream blocked = new InputStream() {
			@Override
			public int read() throws InterruptedIOException {
				throw new InterruptedIOException();
			}
		};
		try {
			new PollingInputStream(blocked, 2, new NullProgressMonitor()).read();
			fail("Expected a timeout");
		} catch (InterruptedIOException expected) {
			assertEquals("Timeout while reading from input stream", expected.getMessage());
		}
	}

	public void testTimeoutAndPollingWrappersTransferBytes() throws Exception {
		byte[] expected = "CVS protocol bytes".getBytes(StandardCharsets.UTF_8);
		try (InputStream in = new PollingInputStream(
				new TimeoutInputStream(new ByteArrayInputStream(expected), 8, 100, 1000),
				20, new NullProgressMonitor())) {
			assertTrue(Arrays.equals(expected, in.readAllBytes()));
		}
		ByteArrayOutputStream underlying = new ByteArrayOutputStream();
		try (PollingOutputStream out = new PollingOutputStream(
				new TimeoutOutputStream(underlying, 8, 100, 1000), 20, new NullProgressMonitor())) {
			out.write(expected);
		}
		assertTrue(Arrays.equals(expected, underlying.toByteArray()));
	}

	private static ByteArrayInputStream bytes(String text) {
		return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
	}

	private static IResource resource(String name) {
		return (IResource) Proxy.newProxyInstance(IResource.class.getClassLoader(), new Class<?>[] { IResource.class },
				(proxy, method, args) -> {
					switch (method.getName()) {
					case "hashCode": return System.identityHashCode(proxy);
					case "equals": return proxy == args[0];
					case "toString": return name;
					default: throw new UnsupportedOperationException(method.getName());
					}
				});
	}

	private static class MemoryStore extends ResourceVariantByteStore {
		final Map<IResource, byte[]> bytes = new HashMap<>();
		final Set<IResource> known = new HashSet<>();
		boolean disposed;
		boolean ran;
		int depth;

		@Override
		public void dispose() { disposed = true; }
		@Override
		public byte[] getBytes(IResource resource) { return bytes.get(resource); }
		@Override
		public boolean setBytes(IResource resource, byte[] value) {
			known.add(resource);
			return !Arrays.equals(bytes.put(resource, value), value);
		}
		@Override
		public boolean flushBytes(IResource resource, int depth) {
			this.depth = depth;
			bytes.remove(resource);
			return known.remove(resource);
		}
		@Override
		public boolean deleteBytes(IResource resource) {
			bytes.remove(resource);
			return known.add(resource);
		}
		@Override
		public IResource[] members(IResource resource) { return bytes.keySet().toArray(new IResource[0]); }
		@Override
		public void run(IResource root, IWorkspaceRunnable runnable, IProgressMonitor monitor) throws TeamException {
			ran = true;
		}
	}
}
