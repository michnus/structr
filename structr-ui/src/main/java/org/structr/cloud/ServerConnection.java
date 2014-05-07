/*
 *  Copyright (C) 2011 Axel Morgner, structr <structr@structr.org>
 *
 *  This file is part of structr <http://structr.org>.
 *
 *  structr is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  structr is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with structr.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.structr.cloud;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.security.InvalidKeyException;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.digest.DigestUtils;
import org.structr.common.AccessMode;
import org.structr.common.SecurityContext;
import org.structr.common.Syncable;
import org.structr.common.error.FrameworkException;
import org.structr.core.GraphObject;
import org.structr.core.Services;
import org.structr.core.app.App;
import org.structr.core.app.StructrApp;
import org.structr.core.entity.Principal;
import org.structr.core.graph.NodeInterface;
import org.structr.core.graph.RelationshipInterface;
import org.structr.core.graph.Tx;
import org.structr.core.property.PropertyMap;
import org.structr.web.entity.File;
import org.structr.web.entity.User;
import org.structr.web.entity.dom.Page;

/**
 *
 * @author Christian Morgner
 */
public class ServerConnection extends Thread implements ServerContext {

	// the logger
	private static final Logger logger = Logger.getLogger(ServerConnection.class.getName());

	// containers
	private final Map<String, FileNodeDataContainer> fileMap = new LinkedHashMap<>();
	private final Map<String, String> idMap                  = new LinkedHashMap<>();

	// private fields
	private App app                     = StructrApp.getInstance();
	private Cipher encrypter            = null;
	private Cipher decrypter            = null;
	private Receiver receiver           = null;
	private Sender sender               = null;
	private Socket socket               = null;
	private Tx tx                       = null;

	public ServerConnection(final Socket socket) {

		this.socket = socket;

		logger.log(Level.INFO, "CloudService: New connection from {0}", socket.getRemoteSocketAddress());
	}

	@Override
	public void start() {

		// setup read and write threads for the connection
		if (socket.isConnected() && !socket.isClosed()) {

			try {

				decrypter = Cipher.getInstance(CloudService.STREAM_CIPHER);
				encrypter = Cipher.getInstance(CloudService.STREAM_CIPHER);

				// this key is only used for the first two packets
				// of a transmission, it is replaced by the users
				// password hash afterwards.
				setEncryptionKey("StructrInitialEncryptionKey");

				sender   = new Sender(socket, new ObjectOutputStream(new GZIPOutputStream(new CipherOutputStream(socket.getOutputStream(), encrypter), true)));
				receiver = new Receiver(socket, new ObjectInputStream(new GZIPInputStream(new CipherInputStream(socket.getInputStream(), decrypter))));

				receiver.start();
				sender.start();

				// start thread
				super.start();

			} catch (Throwable t) {
				t.printStackTrace();
			}
		}
	}

	@Override
	public void run() {

		while (receiver.isConnected() && sender.isConnected()) {

			try {

				final Message request = receiver.receive();
				if (request != null) {

					final Message response = request.process(this);
					if (response != null) {

						sender.send(response);
						response.postProcess(this);
					}
				}

				Thread.sleep(1);

			} catch (Throwable t) {
				t.printStackTrace();
			}
		}

		shutdown();
	}

	public void shutdown() {

		receiver.finish();
		sender.finish();

		// finish pending transactions on shutdown
		try {
			if (tx != null) {
				tx.close();
			}

		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	@Override
	public NodeInterface storeNode(final DataContainer receivedData) {

		try {
			final NodeDataContainer receivedNodeData = (NodeDataContainer)receivedData;
			final PropertyMap properties             = PropertyMap.databaseTypeToJavaType(SecurityContext.getSuperUserInstance(), receivedNodeData.getType(), receivedNodeData.getProperties());
			final String uuid                        = receivedNodeData.getSourceNodeId();
			NodeInterface newOrExistingNode          = null;

			final NodeInterface existingCandidate = app.nodeQuery().and(GraphObject.id, uuid).getFirst();
			if (existingCandidate != null && existingCandidate instanceof NodeInterface) {

				newOrExistingNode = (NodeInterface)existingCandidate;

				// merge properties
				((Syncable)newOrExistingNode).updateFromPropertyMap(properties);

			} else {

				// create
				newOrExistingNode = app.create(receivedNodeData.getType(), properties);
			}

			idMap.put(receivedNodeData.getSourceNodeId(), newOrExistingNode.getUuid());

			return newOrExistingNode;

		} catch (FrameworkException fex) {
			fex.printStackTrace();
		}

		return null;
	}

	@Override
	public RelationshipInterface storeRelationship(final DataContainer receivedData) {

		try {

			final RelationshipDataContainer receivedRelationshipData = (RelationshipDataContainer)receivedData;
			final String sourceStartNodeId                           = receivedRelationshipData.getSourceStartNodeId();
			final String sourceEndNodeId                             = receivedRelationshipData.getSourceEndNodeId();
			final String uuid                                        = receivedRelationshipData.getRelationshipId();
			final String targetStartNodeId                           = idMap.get(sourceStartNodeId);
			final String targetEndNodeId                             = idMap.get(sourceEndNodeId);

			if (targetStartNodeId != null && targetEndNodeId != null) {

				// Get new start and end node
				final NodeInterface targetStartNode   = (NodeInterface)app.get(targetStartNodeId);
				final NodeInterface targetEndNode     = (NodeInterface)app.get(targetEndNodeId);
				final Class relType                   = receivedRelationshipData.getType();
				final SecurityContext securityContext = SecurityContext.getSuperUserInstance();

				if (targetStartNode != null && targetEndNode != null) {

					final RelationshipInterface existingCandidate = app.relationshipQuery().and(GraphObject.id, uuid).getFirst();
					final PropertyMap properties                  = PropertyMap.databaseTypeToJavaType(securityContext, relType, receivedRelationshipData.getProperties());

					if (existingCandidate != null) {

						// merge properties?
						((Syncable)existingCandidate).updateFromPropertyMap(properties);

						return existingCandidate;

					} else {

						return app.create(targetStartNode, targetEndNode, relType, properties);
					}
				}

			}

			logger.log(Level.WARNING, "Could not store relationship {0} -> {1}", new Object[]{sourceStartNodeId, sourceEndNodeId});

		} catch (FrameworkException fex) {
			fex.printStackTrace();
		}

		return null;
	}

//	private void handlePullRequests() {
//
//		Runnable r = new Runnable() {
//
//			@Override
//			public void run() {
//
//				try {
//					Thread.sleep(2000);
//
//					synchronized (pullRequests) {
//
//						final App app = StructrApp.getInstance();
//
//						for (Iterator<PullNodeRequestContainer> it = pullRequests.iterator(); it.hasNext();) {
//							PullNodeRequestContainer request = it.next();
//
//							// swap source and target nodes since we're dealing with a request from the remote's point of view!
//							NodeInterface sourceNode = (NodeInterface)app.get(request.getSourceNodeId());
//							boolean recursive = request.isRecursive();
//
//							PushNodes pushNodes = app.command(PushNodes.class);
//
//							final User remoteUser           = request.getRemoteUser();
//							final String password           = null;
//							final String remoteTargetNodeId = request.getTargetNodeId();
//							final String remoteHostValue    = request.getRemoteHost();
//							final Integer remoteTcpPort     = request.getRemoteTcpPort();
//							final Integer remoteUdpPort     = request.getRemoteUdpPort();
//
//							pushNodes.pushNodes(remoteUser, password, sourceNode, remoteTargetNodeId, remoteHostValue, remoteTcpPort, remoteUdpPort, recursive);
//
//							it.remove();
//						}
//					}
//
//				} catch (Throwable t) {
//					logger.log(Level.WARNING, "Error while handling pull requests: {0}", t);
//				}
//			}
//		};
//
//		new Thread(r, "PullRequestThread").start();
//	}

	@Override
	public void beginTransaction() {
		tx = app.tx();
	}

	@Override
	public void commitTransaction() {

		try {

			tx.success();

		} catch (Throwable t) {

			// do not catch specific exception only, we need to be able to shut
			// down the connection gracefully, so we must make sure not to be
			// interrupted here

			t.printStackTrace();
		}
	}

	@Override
	public void endTransaction() {

		try {

			tx.close();

		} catch (Throwable t) {

			// do not catch specific exception only, we need to be able to shut
			// down the connection gracefully, so we must make sure not to be
			// interrupted here

			t.printStackTrace();
		}
	}

	@Override
	public void closeConnection() {

		shutdown();

		try {

			socket.close();

		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	@Override
	public void ack(String message, int sequenceNumber) {
	}

	@Override
	public Principal getUser(String userName) {

		try {

			return app.nodeQuery(User.class).andName(userName).getFirst();

		} catch (Throwable t) {
			t.printStackTrace();
		}

		return null;
	}

	@Override
	public void impersonateUser(final Principal principal) throws FrameworkException {
		app = StructrApp.getInstance(SecurityContext.getInstance(principal, AccessMode.Backend));
	}

	@Override
	public void beginFile(final FileNodeDataContainer container) {
		fileMap.put(container.sourceNodeId, container);
	}

	@Override
	public void finishFile(final FileNodeEndChunk endChunk) {

		final FileNodeDataContainer container = fileMap.get(endChunk.getContainerId());
		if (container == null) {

			logger.log(Level.WARNING, "Received file end chunk for ID {0} without file, this should not happen!", endChunk.getContainerId());

		} else {

			container.flushAndCloseTemporaryFile();

			final NodeInterface newNode = storeNode(container);
			final String filesPath      = StructrApp.getConfigurationValue(Services.FILES_PATH);
			final String relativePath   = newNode.getProperty(File.relativeFilePath);
			String newPath              = null;

			if (filesPath.endsWith("/")) {

				newPath = filesPath + relativePath;

			} else {

				newPath = filesPath + "/" + relativePath;
			}

			try {
				container.persistTemporaryFile(newPath);

			} catch (Throwable t) {

				// do not catch specific exception only, we need to be able to shut
				// down the connection gracefully, so we must make sure not to be
				// interrupted here
				t.printStackTrace();
			}
		}
	}

	@Override
	public void fileChunk(final FileNodeChunk chunk) {

		final FileNodeDataContainer container = fileMap.get(chunk.getContainerId());

		if (container == null) {

			logger.log(Level.WARNING, "Received file chunk for ID {0} without file, this should not happen!", chunk.getContainerId());

		} else {

			container.addChunk(chunk);
		}
	}

	@Override
	public void setEncryptionKey(final String key) throws InvalidKeyException {

		try {
			final int maxKeyLen    = Cipher.getMaxAllowedKeyLength(CloudService.STREAM_CIPHER);
			SecretKeySpec skeySpec = new SecretKeySpec(CloudService.trimToSize(DigestUtils.sha256(key), maxKeyLen), CloudService.STREAM_CIPHER);

			logger.log(Level.INFO, "Maximum allowed key size for stream encryption cipher {0}: {1}", new Object[] { CloudService.STREAM_CIPHER, maxKeyLen } );

			decrypter.init(Cipher.DECRYPT_MODE, skeySpec);
			encrypter.init(Cipher.ENCRYPT_MODE, skeySpec);

		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	@Override
	public List<String> listPages() throws FrameworkException {

		final List<String> pages = new LinkedList<>();

		for (final Page page : app.nodeQuery(Page.class).getAsList()) {

			pages.add(page.getName());
		}

		return pages;
	}
}