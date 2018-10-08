/*
 * File    : GlobalManagerStatsImpl.java
 * Created : 21-Oct-2003
 * By      : stuff
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details ( see the LICENSE file ).
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package com.biglybt.core.global.impl;

import java.net.InetAddress;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * @author parg
 *
 */

import com.biglybt.core.config.COConfigurationManager;
import com.biglybt.core.download.DownloadManager;
import com.biglybt.core.download.DownloadManagerPeerListener;
import com.biglybt.core.global.GlobalManagerAdapter;
import com.biglybt.core.global.GlobalManagerStats;
import com.biglybt.core.global.GlobalManagerStats.RemoteCountryStats;
import com.biglybt.core.global.GlobalManagerStats.RemoteStats;
import com.biglybt.core.peer.PEPeer;
import com.biglybt.core.peer.PEPeerListener;
import com.biglybt.core.peer.PEPeerManager;
import com.biglybt.core.peer.PEPeerStats;
import com.biglybt.core.peer.util.PeerUtils;
import com.biglybt.core.peermanager.piecepicker.util.BitFlags;
import com.biglybt.core.util.AERunnable;
import com.biglybt.core.util.AsyncDispatcher;
import com.biglybt.core.util.Average;
import com.biglybt.core.util.CopyOnWriteList;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.GeneralUtils;
import com.biglybt.core.util.RandomUtils;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.SimpleTimer.TimerTickReceiver;
import com.biglybt.core.util.average.AverageFactory;
import com.biglybt.core.util.average.MovingImmediateAverage;


public class
GlobalManagerStatsImpl
	implements GlobalManagerStats, TimerTickReceiver
{
	private final GlobalManagerImpl		manager;

	private long smooth_last_sent;
	private long smooth_last_received;

	private int current_smoothing_window 	= GeneralUtils.getSmoothUpdateWindow();
	private int current_smoothing_interval 	= GeneralUtils.getSmoothUpdateInterval();

	private MovingImmediateAverage smoothed_receive_rate 	= GeneralUtils.getSmoothAverage();
	private MovingImmediateAverage smoothed_send_rate 		= GeneralUtils.getSmoothAverage();


	private long total_data_bytes_received;
    private long total_protocol_bytes_received;

	private long totalDiscarded;

    private long total_data_bytes_sent;
    private long total_protocol_bytes_sent;

    private int	data_send_speed_at_close;

	private final Average data_receive_speed = Average.getInstance(1000, 10);  //average over 10s, update every 1000ms
    private final Average protocol_receive_speed = Average.getInstance(1000, 10);  //average over 10s, update every 1000ms
	private final Average data_receive_speed_no_lan = Average.getInstance(1000, 10);  //average over 10s, update every 1000ms
    private final Average protocol_receive_speed_no_lan = Average.getInstance(1000, 10);  //average over 10s, update every 1000ms

	private final Average data_send_speed = Average.getInstance(1000, 10);  //average over 10s, update every 1000ms
    private final Average protocol_send_speed = Average.getInstance(1000, 10);  //average over 10s, update every 1000ms
	private final Average data_send_speed_no_lan = Average.getInstance(1000, 10);  //average over 10s, update every 1000ms
    private final Average protocol_send_speed_no_lan = Average.getInstance(1000, 10);  //average over 10s, update every 1000ms

    private static final Object	PEER_DATA_INIT_KEY 	= new Object();
    private static final Object	PEER_DATA_KEY 		= new Object();
    private static final Object	PEER_DATA_FINAL_KEY = new Object();
   
    private List<PEPeer>	removed_peers = new LinkedList<>();
    
	protected
	GlobalManagerStatsImpl(
		GlobalManagerImpl	_manager )
	{
		manager = _manager;

		load();

		manager.addListener(
			new GlobalManagerAdapter()
			{
				public void
				downloadManagerAdded(
					DownloadManager	dm )
				{
					dm.addPeerListener(
						new DownloadManagerPeerListener(){
							
							@Override
							public void 
							peerAdded(
								PEPeer peer)
							{
								if ( peer.getPeerState() ==  PEPeer.TRANSFERING ){
									
									saveInitialStats( peer );
									
								}else{
									peer.addListener(
										new PEPeerListener(){
											
											@Override
											public void 
											stateChanged(
												PEPeer peer, 
												int new_state)
											{
												if ( new_state == PEPeer.TRANSFERING ){
													
													saveInitialStats( peer );
													
													peer.removeListener( this );
												}
											}
											
											@Override
											public void sentBadChunk(PEPeer peer, int piece_num, int total_bad_chunks){
											}
											
											@Override
											public void removeAvailability(PEPeer peer, BitFlags peerHavePieces){
											}
											
											@Override
											public void addAvailability(PEPeer peer, BitFlags peerHavePieces){
											}
										});
								}
							}
							
							private void
							saveInitialStats(
								PEPeer		peer )
							{
								PEPeerStats stats = peer.getStats();
								
								long sent = stats.getTotalDataBytesSent();
								long recv = stats.getTotalDataBytesReceived();
								
									// account for the fact that we remember stats across peer reconnects...
								
								if ( sent + recv > 0 ){

									peer.setUserData( PEER_DATA_INIT_KEY, new long[]{ sent, recv });
								}
							}
							
							@Override
							public void 
							peerRemoved(
								PEPeer peer)
							{
								PEPeerStats stats = peer.getStats();
								
								long sent = stats.getTotalDataBytesSent();
								long recv = stats.getTotalDataBytesReceived();
								
								if ( sent + recv > 0 ){

										// gotta snapshot these values now in case this stats object
										// is re-associated :(
									
									peer.setUserData( PEER_DATA_FINAL_KEY, new long[]{ sent, recv });
									
									synchronized( PEER_DATA_KEY ){
									
										removed_peers.add( peer );
									}
								}
							}
							
							@Override
							public void 
							peerManagerWillBeAdded(
									PEPeerManager manager)
							{
							}
							
							@Override
							public void 
							peerManagerRemoved(
								PEPeerManager manager)
							{
							}
							
							@Override
							public void 
							peerManagerAdded(
								PEPeerManager manager)
							{
							}
						});
				}
			}, true );
		
		SimpleTimer.addTickReceiver( this );
	}

	protected void
	load()
	{
		data_send_speed_at_close	= COConfigurationManager.getIntParameter( "globalmanager.stats.send.speed.at.close", 0 );
	}

	protected void
	save()
	{
		COConfigurationManager.setParameter( "globalmanager.stats.send.speed.at.close", getDataSendRate());
	}

	@Override
	public int
	getDataSendRateAtClose()
	{
		return( data_send_speed_at_close );
	}

  			// update methods

	@Override
	public void discarded(int length) {
		this.totalDiscarded += length;
	}

	@Override
	public void dataBytesReceived(int length, boolean LAN){
		total_data_bytes_received += length;
		if ( !LAN ){
			data_receive_speed_no_lan.addValue(length);
		}
		data_receive_speed.addValue(length);
	}


	@Override
	public void protocolBytesReceived(int length, boolean LAN ){
		total_protocol_bytes_received += length;
		if ( !LAN ){
			protocol_receive_speed_no_lan.addValue(length);
		}
		protocol_receive_speed.addValue(length);
	}

	@Override
	public void dataBytesSent(int length, boolean LAN) {
		total_data_bytes_sent += length;
		if ( !LAN ){
			data_send_speed_no_lan.addValue(length);
		}
		data_send_speed.addValue(length);
	}

	@Override
	public void protocolBytesSent(int length, boolean LAN) {
		total_protocol_bytes_sent += length;
		if ( !LAN ){
			protocol_send_speed_no_lan.addValue(length);
		}
		protocol_send_speed.addValue(length);
	}

	@Override
	public int getDataReceiveRate() {
		return (int)data_receive_speed.getAverage();
	}
	@Override
	public int getDataReceiveRateNoLAN() {
		return (int)data_receive_speed_no_lan.getAverage();
	}
	@Override
	public int getDataReceiveRateNoLAN(int average_period) {
		return (int)(average_period<=0?data_receive_speed_no_lan.getAverage():data_receive_speed_no_lan.getAverage(average_period));
	}
	@Override
	public int getProtocolReceiveRate() {
		return (int)protocol_receive_speed.getAverage();
	}
	@Override
	public int getProtocolReceiveRateNoLAN() {
		return (int)protocol_receive_speed_no_lan.getAverage();
	}
	@Override
	public int getProtocolReceiveRateNoLAN(int average_period) {
		return (int)(average_period<=0?protocol_receive_speed_no_lan.getAverage():protocol_receive_speed_no_lan.getAverage(average_period));
	}

	@Override
	public int getDataAndProtocolReceiveRate(){
		return((int)( protocol_receive_speed.getAverage() + data_receive_speed.getAverage()));
	}

	@Override
	public int getDataSendRate() {
		return (int)data_send_speed.getAverage();
	}
	@Override
	public int getDataSendRateNoLAN() {
		return (int)data_send_speed_no_lan.getAverage();
	}
	@Override
	public int getDataSendRateNoLAN(int average_period) {
		return (int)(average_period<=0?data_send_speed_no_lan.getAverage():data_send_speed_no_lan.getAverage(average_period));
	}

	@Override
	public int getProtocolSendRate() {
		return (int)protocol_send_speed.getAverage();
	}
	@Override
	public int getProtocolSendRateNoLAN() {
		return (int)protocol_send_speed_no_lan.getAverage();
	}
	@Override
	public int getProtocolSendRateNoLAN(int average_period) {
		return (int)(average_period<=0?protocol_send_speed_no_lan.getAverage():protocol_send_speed_no_lan.getAverage(average_period));
	}

	@Override
	public int getDataAndProtocolSendRate(){
		return((int)( protocol_send_speed.getAverage() + data_send_speed.getAverage()));
	}

    @Override
    public long getTotalDataBytesSent() {
    	return total_data_bytes_sent;
    }

    @Override
    public long getTotalProtocolBytesSent() {
    	return total_protocol_bytes_sent;
    }


    @Override
    public long getTotalDataBytesReceived() {
    	return total_data_bytes_received;
    }

    @Override
    public long getTotalProtocolBytesReceived() {
    	return total_protocol_bytes_received;
    }


    public long getTotalDiscardedRaw() {
    	return totalDiscarded;
    }

    @Override
    public long getTotalSwarmsPeerRate(boolean downloading, boolean seeding )
    {
    	return( manager.getTotalSwarmsPeerRate(downloading,seeding));
    }


    private static class
    PeerDetails
    {
    	String		cc;
    	long		sent;
    	long		recv;
    	
    	PeerDetails(
    		String		_cc )
    	{
    		cc	= _cc;
    	}
    }
    
    private static class
    CountryDetailsImpl
    	implements CountryDetails
    {
    	String		cc;
    	
    	long		total_sent;
    	long		total_recv;
    	
    	long		last_sent;
    	long		last_recv;
    	
       	com.biglybt.core.util.average.Average		sent_average	= AverageFactory.MovingImmediateAverage( 3 );
       	com.biglybt.core.util.average.Average		recv_average	= AverageFactory.MovingImmediateAverage( 3 );
       	
       	CountryDetailsImpl(
       		String		_cc )
       	{
       		cc	= _cc;
       	}
       	
       	public String
       	getCC()
       	{
       		return( cc );
       	}
       	
		public long
		getTotalSent()
		{
			return( total_sent );
		}
		
		public long
		getLatestSent()
		{
			return( last_sent );
		}
		
		public long
		getAverageSent()
		{
			return((long)sent_average.getAverage());
		}
		
		public long
		getTotalReceived()
		{
			return( total_recv );
		}
		
		public long
		getLatestReceived()
		{
			return( last_recv );
		}
		
		public long
		getAverageReceived()
		{
			return((long)recv_average.getAverage());
		}
		
       	public String
       	toString()
       	{
       		return( "sent: " + total_sent + "/" + last_sent + "/" + (long)sent_average.getAverage() + ", " + 
       				"recv: " + total_recv + "/" + last_recv + "/" + (long)recv_average.getAverage() );
       	}
    }
    
	
	@Override
	public long
	getSmoothedSendRate()
	{
		return((long)(smoothed_send_rate.getAverage()/current_smoothing_interval));
	}

	@Override
	public long
	getSmoothedReceiveRate()
	{
		return((long)(smoothed_receive_rate.getAverage()/current_smoothing_interval));
	}
	
	
	
    private Map<String,CountryDetails>		country_details = new ConcurrentHashMap<>();
    private CountryDetailsImpl				country_total	= new CountryDetailsImpl( "" );
    
    {
    	country_details.put( country_total.cc, country_total );
    }
    
	public Iterator<CountryDetails>
	getCountryDetails()
	{
		return( country_details.values().iterator());
	}
	
	private AsyncDispatcher stats_dispatcher = new AsyncDispatcher( "GMStats", 1000 );
	
	@Override
	public void
	tick(
		long		mono_now,
		int			tick_count )
	{
		if ( tick_count % current_smoothing_interval == 0 ){

			int	current_window = GeneralUtils.getSmoothUpdateWindow();

			if ( current_smoothing_window != current_window ){

				current_smoothing_window 	= current_window;
				current_smoothing_interval	= GeneralUtils.getSmoothUpdateInterval();
				smoothed_receive_rate 		= GeneralUtils.getSmoothAverage();
				smoothed_send_rate 			= GeneralUtils.getSmoothAverage();
			}

			long	up 		= total_data_bytes_sent + total_protocol_bytes_sent;
			long	down 	= total_data_bytes_received + total_protocol_bytes_received;

			smoothed_send_rate.update( up - smooth_last_sent );
			smoothed_receive_rate.update( down - smooth_last_received );

			smooth_last_sent 		= up;
			smooth_last_received 	= down;
		}
		
		if ( tick_count % 60 == 0 ){
			
			stats_dispatcher.dispatch(
				new AERunnable(){
					
					@Override
					public void runSupport()
					{
						List<List<PEPeer>>	peer_lists = new LinkedList<>();
						
						synchronized( PEER_DATA_KEY ){
							
							if ( !removed_peers.isEmpty()){
						
								peer_lists.add( removed_peers );
								
								removed_peers = new LinkedList<>();
							}
						}
						
						for ( DownloadManager dm: manager.getDownloadManagers()){
							
							PEPeerManager pm = dm.getPeerManager();
							
							if ( pm != null ){
								
								List<PEPeer> peers = pm.getPeers();
								
								if ( !peers.isEmpty()){
									
									peer_lists.add( peers );
								}
							}
						}
						
							// single threaded here remember
									
						Set<CountryDetailsImpl>	updated = new HashSet<>();
						
						Map<String,long[]>	updates = new HashMap<>();
						
						for ( List<PEPeer> peers: peer_lists ){
						
							for ( PEPeer peer: peers ){
								
								if ( peer.isLANLocal()){
									
									continue;
								}
								
								PEPeerStats stats = peer.getStats();
								
								long sent = stats.getTotalDataBytesSent();
								long recv = stats.getTotalDataBytesReceived();
			
								if ( sent + recv > 0 ){
									
									PeerDetails details = (PeerDetails)peer.getUserData( PEER_DATA_KEY );
									
									if ( details == null ){
										
										String[] dets = PeerUtils.getCountryDetails(peer);
				
										details = new PeerDetails( dets==null||dets.length<1?"??":dets[0] );
										
										long[] init_data = (long[])peer.getUserData( PEER_DATA_INIT_KEY );
										
										if ( init_data != null ){
											
											details.sent	= init_data[0];
											details.recv	= init_data[1];
										}
										
										peer.setUserData( PEER_DATA_KEY, details );	
									}
										
									long[] final_data = (long[])peer.getUserData( PEER_DATA_FINAL_KEY );
									
									if ( final_data != null ){
										
										sent	= final_data[0];
										recv	= final_data[1];
									}
									
									long diff_sent	= sent - details.sent;
									long diff_recv	= recv - details.recv;
															
									if ( diff_sent + diff_recv > 0 ){
										
										String cc = details.cc;
			
										long[] totals = updates.get( cc );
										
										if ( totals == null ){
											
											totals = new long[]{ diff_sent, diff_recv };
											
											updates.put( cc, totals );
											
										}else{
										
											totals[0] += diff_sent;
											totals[1] += diff_recv;
										}
									}
									
									details.sent 	= sent;
									details.recv	= recv;
								}
							}
						}
						
						long	total_diff_sent	= 0;
						long	total_diff_recv	= 0;
			
						for ( Map.Entry<String,long[]> entry: updates.entrySet()){
							
							String	cc 		= entry.getKey();
							long[]	totals 	= entry.getValue();
								
							long	diff_sent	= totals[0];
							long	diff_recv	= totals[1];
							
							CountryDetailsImpl cd = (CountryDetailsImpl)country_details.get( cc );
							
							if ( cd == null ){
								
								cd = new CountryDetailsImpl( cc );
								
								country_details.put( cc, cd );
							}
							
							updated.add( cd );
							
							if ( diff_sent > 0 ){
							
								cd.last_sent	= diff_sent;
								cd.total_sent	+= diff_sent;
			
								cd.sent_average.update( diff_sent );
								
								total_diff_sent += diff_sent;
								
							}else{
								
								cd.last_sent	= 0;
								
								cd.sent_average.update( diff_sent );
							}
							
							if ( diff_recv > 0 ){
								
								cd.last_recv	= diff_recv;
								cd.total_recv	+= diff_recv;
			
								cd.recv_average.update( diff_recv );
								
								total_diff_recv += diff_recv;
								
							}else{
								
								cd.last_recv	= 0;
								
								cd.recv_average.update( diff_recv );
							}
						}
						
						updated.add( country_total );
						
						if ( total_diff_sent > 0 ){
							
							country_total.last_sent		= total_diff_sent;
							country_total.total_sent	+= total_diff_sent;
							
							country_total.sent_average.update( total_diff_sent );
							
						}else{
							
							country_total.last_sent		= 0;
							
							country_total.sent_average.update( 0 );
						}		
						
						if ( total_diff_recv > 0 ){
							
							country_total.last_recv		= total_diff_recv;
							country_total.total_recv	+= total_diff_recv;
							
							country_total.recv_average.update( total_diff_recv );
							
						}else{
							
							country_total.last_recv		= 0;
							
							country_total.recv_average.update( 0 );
						}
						
						for ( CountryDetails cd: country_details.values()){
							
							if ( !updated.contains( cd )){
								
								CountryDetailsImpl cdi = (CountryDetailsImpl)cd;
								
								cdi.last_recv 	= 0;
								cdi.last_sent	= 0;
								
								cdi.recv_average.update( 0 );
								cdi.sent_average.update( 0 );
							}
						}
					}
				});	
		}
		
		if ( tick_count % 10 == 0 ){
			
			stats_dispatcher.dispatch(
				new AERunnable(){
					
					@Override
					public void 
					runSupport()
					{							
						for ( Iterator<RemoteStats> it = pending_stats.values().iterator(); it.hasNext();){
							
							RemoteStats stats = it.next();
							
							it.remove();
							
							addRemoteStats( stats );
						}
					}
				});
		}
		
		if ( tick_count % 30 == 0 ){
			
			RemoteStats	stats = 
				new RemoteStats()
				{
					final String[] CCS = { "US", "GB", "FR", "IT", "AU", "DE" };
										
					RemoteCountryStats[]	stats = new RemoteCountryStats[Math.abs(RandomUtils.nextInt(4))+1];
					
					{
						for ( int i=0; i<stats.length;i++){
					
							String cc = CCS[RandomUtils.nextAbsoluteInt() % CCS.length ];

							stats[i] = 
								new RemoteCountryStats()
								{
									public String 
									getCC()
									{
										return( cc );
									}
									
									public long
									getAverageSent()
									{
										return( 100 );
									}
								};
						}
					}
					
					public InetAddress
					getRemoteAddress()
					{
						byte[] bytes = new byte[4];
						
						RandomUtils.nextBytes( bytes );
						
						try{
							return( InetAddress.getByAddress( bytes ));
							
						}catch( Throwable e ){
							
							return( null );
						}
					}
					
					public long
					getMonoTime()
					{
						return( SystemTime.getMonotonousTime());
					}
					
					public RemoteCountryStats[]
					getStats()		
					{
						return( stats );
					}
				};
				
			receiveRemoteStats( stats );
		}
	}

	private ConcurrentHashMap<InetAddress,RemoteStats>	pending_stats = new ConcurrentHashMap<>();
	
	public void
	receiveRemoteStats(
		RemoteStats		stats )
	{
		pending_stats.put( stats.getRemoteAddress(), stats );
	}
	
	private Map<String,Map<String,Long>>	aggregate_stats = new ConcurrentHashMap<>();
	
	private static class
	HistoryEntry
	{
		final String				cc;
		final long					time;
		final RemoteCountryStats[]	stats;
		
		private
		HistoryEntry(
			String		_cc,
			RemoteStats	_stats )
		{
			cc		= _cc;
			stats	= _stats.getStats();
			time	= _stats.getMonoTime(); 
		}
	}
	
	private Set<HistoryEntry>					stats_history	= 
		new TreeSet<>(
			new Comparator<HistoryEntry>()
			{
				public int 
				compare(
					HistoryEntry o1, 
					HistoryEntry o2)
				{
					return( Long.compare(o1.time, o2.time ));
				}
			});
	
	private void
	addRemoteStats(
		RemoteStats		stats )
	{
			// add new entry
		
		{
			String[] o_details = PeerUtils.getCountryDetails(stats.getRemoteAddress());
			
			String originator_cc;
			
			if ( o_details == null || o_details.length < 1 ){
			
				originator_cc = "??";
				
			}else{
			
				originator_cc = o_details[0];
			}
			
			stats_history.add( new HistoryEntry( originator_cc, stats ));
			
			Map<String,Long> map = aggregate_stats.get( originator_cc );
			
			if ( map == null ){
				
				map = new ConcurrentHashMap<>();
				
				aggregate_stats.put( originator_cc, map );
			}
			
			RemoteCountryStats[] rcs = stats.getStats();
			
			for ( RemoteCountryStats rc: rcs ){
				
				String cc = rc.getCC();
				
				long	ave = rc.getAverageSent();
				
				if ( ave > 0 ){
					
					Long	val = map.get( cc );
					
					if ( val == null ){
						
						map.put( cc, ave );
						
					}else{
						
						map.put( cc, val + ave );
					}
				}
			}
		}
		
			// remove old
		
		{
			long	now = SystemTime.getMonotonousTime();
	
			Iterator<HistoryEntry>	it = stats_history.iterator();
			
			while( it.hasNext()){
				
				HistoryEntry entry = it.next();
					
				if ( 	stats_history.size() > 100 ||
						now - entry.time > 10*60*1000 ){
					
					it.remove();
					
					String originator_cc = entry.cc;
					
					Map<String,Long> map = aggregate_stats.get( originator_cc );
			
					if ( map == null ){
						
						Debug.out( "inconsistent");
						
						return;
					}
					
					for ( RemoteCountryStats rc: entry.stats ){
						
						String cc = rc.getCC();
						
						long	ave = rc.getAverageSent();
						
						if ( ave > 0 ){
							
							Long	val = map.get( cc );
							
							if ( val == null ){
								
								Debug.out( "inconsistent");
								
							}else{
								
								long temp = val - ave;
								
								if ( temp < 0 ){
									
									Debug.out( "inconsistent");
									
								}else{
									
									if ( temp == 0 ){
										
										map.remove( cc );
										
									}else{
										
										map.put( cc,  temp );
									}
								}
							}
						}
					}
					
					if ( map.isEmpty()){
						
						aggregate_stats.remove( originator_cc );
					}
				}else{
					
					break;
				}
			}
		}
	}
	
	public Map<String,Map<String,Long>>
	getAggregateRemoteStats()
	{
		return( aggregate_stats );
	}
}