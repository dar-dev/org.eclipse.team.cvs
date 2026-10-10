/*******************************************************************************
 * Copyright (c) 2000, 2017 IBM Corporation and others.
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
package org.eclipse.team.internal.ccvs.core.syncinfo;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.team.core.TeamException;
import org.eclipse.team.core.variants.ResourceVariantByteStore;

/**
 * Stores only remote variants that differ from the base, falling back to the
 * base when the remote variant is stale or has not been fetched.
 * <p>
 * This is the former Team Core DescendantResourceVariantByteStore implementation,
 * retained locally because Eclipse 4.42 removed that internal class. Both trees
 * must represent the same line of descent (for example, the same CVS branch).
 */
public abstract class DescendantResourceVariantByteStore extends ResourceVariantByteStore {

	private final ResourceVariantByteStore baseStore;
	private final ResourceVariantByteStore remoteStore;

	public DescendantResourceVariantByteStore(ResourceVariantByteStore baseCache, ResourceVariantByteStore remoteCache) {
		this.baseStore = baseCache;
		this.remoteStore = remoteCache;
	}

	/** Dispose the remote cache, but not the base cache. */
	@Override
	public void dispose() {
		remoteStore.dispose();
	}

	@Override
	public byte[] getBytes(IResource resource) throws TeamException {
		byte[] remoteBytes = remoteStore.getBytes(resource);
		byte[] baseBytes = baseStore.getBytes(resource);
		if (baseBytes == null) {
			return remoteBytes;
		}
		if (remoteBytes == null) {
			if (isVariantKnown(resource)) {
				// The remote is known not to exist.
				// TODO: This does not take the line of descent into consideration.
				return null;
			}
			// The remote was never queried or was the same as the base.
			return baseBytes;
		}
		if (isDescendant(resource, baseBytes, remoteBytes)) {
			return remoteBytes;
		}
		// The remote bytes are stale (not on the same line of descent).
		return baseBytes;
	}

	@Override
	public boolean setBytes(IResource resource, byte[] bytes) throws TeamException {
		byte[] baseBytes = baseStore.getBytes(resource);
		if (baseBytes != null && equals(baseBytes, bytes)) {
			// Save space by removing the remote bytes and using the base.
			return remoteStore.flushBytes(resource, IResource.DEPTH_ZERO);
		}
		return remoteStore.setBytes(resource, bytes);
	}

	@Override
	public boolean flushBytes(IResource resource, int depth) throws TeamException {
		return remoteStore.flushBytes(resource, depth);
	}

	/**
	 * Whether the remote variant was fetched, including a known deletion.
	 */
	public abstract boolean isVariantKnown(IResource resource) throws TeamException;

	/** Whether the remote revision is later on the same line of descent. */
	protected abstract boolean isDescendant(IResource resource, byte[] baseBytes, byte[] remoteBytes) throws TeamException;

	@Override
	public boolean deleteBytes(IResource resource) throws TeamException {
		return remoteStore.deleteBytes(resource);
	}

	protected ResourceVariantByteStore getBaseStore() {
		return baseStore;
	}

	protected ResourceVariantByteStore getRemoteStore() {
		return remoteStore;
	}

	@Override
	public IResource[] members(IResource resource) throws TeamException {
		IResource[] remoteMembers = remoteStore.members(resource);
		IResource[] baseMembers = baseStore.members(resource);
		Set<IResource> members = new HashSet<>();
		Collections.addAll(members, remoteMembers);
		for (IResource member : baseMembers) {
			// Add the base only if the remote has not marked it as deleted.
			if (!isVariantKnown(member)) {
				members.add(member);
			}
		}
		return members.toArray(new IResource[members.size()]);
	}

	@Override
	public void run(IResource root, IWorkspaceRunnable runnable, IProgressMonitor monitor) throws TeamException {
		remoteStore.run(root, runnable, monitor);
	}
}
