package reactor.io.net.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.netty.util.NetUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.Processors;
import reactor.Timers;
import reactor.io.net.NetStreams;
import reactor.io.net.config.ServerSocketOptions;
import reactor.io.net.impl.netty.udp.NettyDatagramServer;
import reactor.io.net.preprocessor.CodecPreprocessor;
import reactor.io.net.tcp.support.SocketUtils;
import reactor.rx.Streams;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
public class UdpServerTests {

	final Logger log = LoggerFactory.getLogger(getClass());

	ExecutorService threadPool;

	@Before
	public void setup() {
		Timers.global();
		threadPool = Executors.newCachedThreadPool();
	}

	@After
	public void cleanup() throws InterruptedException {
		threadPool.shutdown();
		threadPool.awaitTermination(5, TimeUnit.SECONDS);
		Timers.unregisterGlobal();
	}

	@Test
	@Ignore
	public void supportsReceivingDatagrams() throws InterruptedException {
		final int port = SocketUtils.findAvailableUdpPort();
		final CountDownLatch latch = new CountDownLatch(4);

		final ReactorDatagramServer<byte[], byte[]> server = NetStreams.udpServer(
		  s -> s.listen(port)
		        .preprocessor(CodecPreprocessor.byteArray())
		);

		server.start(ch -> {
			ch.log().consume(bytes -> {
				if (bytes.length == 1024) {
					latch.countDown();
				}
			});
			return Streams.never();
		}).onComplete(p -> {
			try {
				DatagramChannel udp = DatagramChannel.open();
				udp.configureBlocking(true);
				udp.connect(new InetSocketAddress(InetAddress.getLocalHost(), port));

				byte[] data = new byte[1024];
				new Random().nextBytes(data);
				for (int i = 0; i < 4; i++) {
					udp.write(ByteBuffer.wrap(data));
				}

				udp.close();
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		});

		assertThat("latch was counted down", latch.await(10, TimeUnit.SECONDS));
		server.shutdown().await();
	}

	@Test
	@SuppressWarnings("unchecked")
	public void supportsUdpMulticast() throws Exception {
		final int port = SocketUtils.findAvailableUdpPort();
		final CountDownLatch latch = new CountDownLatch(4 ^ 2);

		final InetAddress multicastGroup = InetAddress.getByName("230.0.0.1");
		final NetworkInterface multicastInterface = findMulticastEnabledIPv4Interface();
		log.info("Using network interface '{}' for multicast", multicastInterface);
		final Collection<ReactorDatagramServer<byte[], byte[]>> servers = new ArrayList<>();

		for (int i = 0; i < 4; i++) {
			ReactorDatagramServer<byte[], byte[]> server = NetStreams.<byte[], byte[]>udpServer(
			  NettyDatagramServer.class,
			  spec -> spec
				.listen(port)
				.options(new ServerSocketOptions()
				  .reuseAddr(true)
				  .protocolFamily(StandardProtocolFamily.INET))
				.preprocessor(CodecPreprocessor.byteArray())
			);

			server.start(ch -> {
				ch.log().consume(bytes -> {
					//log.info("{} got {} bytes", ++count, bytes.length);
					if (bytes.length == 1024) {
						latch.countDown();
					}
				});
				return Streams.never();
			}).onComplete(b -> {
				try {
					for (Enumeration<NetworkInterface> ifaces =
					     NetworkInterface.getNetworkInterfaces();
					     ifaces.hasMoreElements(); ) {
						NetworkInterface iface = ifaces.nextElement();
						if (isMulticastEnabledIPv4Interface(iface)) {
							server.join(multicastGroup, iface);
						}
					}
				}
				catch (Throwable t) {
					throw new IllegalStateException(t);
				}
			}).await();

			servers.add(server);
		}

		for (int i = 0; i < Processors.DEFAULT_POOL_SIZE; i++) {
			threadPool.submit(() -> {
				try {
					MulticastSocket multicast = new MulticastSocket();
					multicast.joinGroup(new InetSocketAddress(multicastGroup, port), multicastInterface);

					byte[] data = new byte[1024];
					new Random().nextBytes(data);

					multicast.send(new DatagramPacket(data, data.length, multicastGroup, port));

					multicast.close();
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}).get(5, TimeUnit.SECONDS);
		}

		latch.await(5, TimeUnit.SECONDS);
		assertThat("latch was not counted down enough: "+latch.getCount()+" left on "+(4 ^ 2), latch.getCount() == 0 );

		for (ReactorDatagramServer s : servers) {
			s.shutdown().await();
		}
	}

	private boolean isMulticastEnabledIPv4Interface(NetworkInterface iface) throws SocketException {
		if (!iface.supportsMulticast() || !iface.isUp()) {
			return false;
		}

		for (Enumeration<InetAddress> i = iface.getInetAddresses(); i.hasMoreElements(); ) {
			InetAddress address = i.nextElement();
			if (address.getClass() == Inet4Address.class) {
				return true;
			}
		}

		return false;
	}

	private NetworkInterface findMulticastEnabledIPv4Interface() throws SocketException {
		if (isMulticastEnabledIPv4Interface(NetUtil.LOOPBACK_IF)) {
			return NetUtil.LOOPBACK_IF;
		}

		for (Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces(); ifaces.hasMoreElements();
		  ) {
			NetworkInterface iface = ifaces.nextElement();
			if (isMulticastEnabledIPv4Interface(iface)) {
				return iface;
			}
		}

		throw new UnsupportedOperationException("This test requires a multicast enabled IPv4 network interface, but " +
		  "none" +
		  " " +
		  "were found");
	}
}
