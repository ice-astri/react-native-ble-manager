package it.innove;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.util.Log;
import com.facebook.react.bridge.*;

import static com.facebook.react.bridge.UiThreadUtil.runOnUiThread;

import android.os.Handler;
import java.lang.Runnable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LegacyScanManager extends ScanManager {

	private List<String> matchNames = null;

	public LegacyScanManager(ReactApplicationContext reactContext, BleManager bleManager) {
		super(reactContext, bleManager);
	}

	@Override
	public void stopScan(Callback callback) {
		// update scanSessionId to prevent stopping next scan by running timeout thread
		scanSessionId.incrementAndGet();
		Log.i(bleManager.LOG_TAG, "[legacy] stopScan() scanSessionId [" + scanSessionId + "]");

		getBluetoothAdapter().stopLeScan(mLeScanCallback);
		callback.invoke();
	}

	private BluetoothAdapter.LeScanCallback mLeScanCallback =
			new BluetoothAdapter.LeScanCallback() {


				@Override
				public void onLeScan(final BluetoothDevice device, final int rssi,
									 final byte[] scanRecord) {
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							//-----------------------------------------------------------------
							Map <Integer,String> ret = ParseRecord(scanRecord);
							String localName = ret.get(0x09); // Hotfix
							//-----------------------------------------------------------------
							String address = device.getAddress();
							Peripheral peripheral = null;

							//------------------------------------------------------------------------------------
							// filter out not valid device
							//------------------------------------------------------------------------------------
							if (localName == null || localName.isEmpty() || address == null ||  address.isEmpty())
							{
								Log.i(bleManager.LOG_TAG, "[legacy] DiscoverPeripheral (ignored, invalid device): [" + device.getName() + "] localName=[" + localName + "] address:[" + address + "]");
								return;
							}
							//------------------------------------------------------------------------------------
							// filter by match names
							//------------------------------------------------------------------------------------
							if(matchNames!=null && !matchNames.contains(localName)){
								Log.i(bleManager.LOG_TAG, "[legacy] DiscoverPeripheral (ignored, mismatch name): [" + device.getName() + "] localName=[" + localName + "] address:[" + address + "]");
								return;
							}

							if (!bleManager.peripherals.containsKey(address)) {
								peripheral = new Peripheral(device, rssi, scanRecord, reactContext);
								bleManager.peripherals.put(device.getAddress(), peripheral);
							} else {
								peripheral = bleManager.peripherals.get(address);
								peripheral.updateRssi(rssi);
								peripheral.updateData(scanRecord);
							}

							WritableMap map = peripheral.asWritableMap();
							//-----------------------------------------------------------------
							// Hotfix: use the result.getScanRecord().getDeviceName() to replace
							// the cached result.getDevice().getName()
							// NOTE: the function BleManager.getDiscoveredPeripherals() and
							// BleManager.getConnectedPeripherals() will not have this hotfix.
							//-----------------------------------------------------------------
							map.putString("name", localName);
							bleManager.sendEvent("BleManagerDiscoverPeripheral", map);
						}
					});
				}


			};

	@Override
	public void scan(ReadableArray serviceUUIDs, final int scanSeconds, ReadableMap options, Callback callback) {
		if (serviceUUIDs.size() > 0) {
			Log.d(bleManager.LOG_TAG, "Filter is not working in pre-lollipop devices");
		}
		getBluetoothAdapter().startLeScan(mLeScanCallback);

		//---------------add options match names for ble device----------
		ReadableArray matchNamesReadableArray = options.getArray("matchNames");
		matchNames = null;
		if(matchNamesReadableArray != null && matchNamesReadableArray.size() > 0){
			matchNames = new ArrayList<String>(matchNamesReadableArray.size());
			for (int index = 0; index < matchNamesReadableArray.size(); index++) {
				if(matchNamesReadableArray.getType(index) == ReadableType.String){
					matchNames.add(matchNamesReadableArray.getString(index));
				}else{
					Log.d(bleManager.LOG_TAG, "Omitted matchNames value at index = "+index);
				}
			}
		}


		if (scanSeconds > 0) {
			final Handler mainHandler = new Handler(this.reactContext.getMainLooper());
			mainHandler.post(new Runnable() {

				private int currentScanSession = scanSessionId.incrementAndGet();

				@Override
				public void run() {
					Log.i(bleManager.LOG_TAG, "[legacy] stop thread create: currentScanSession [" + currentScanSession + "]");
					mainHandler.postDelayed(new Runnable() {
						@Override
						public void run() {
							try {
								BluetoothAdapter btAdapter = getBluetoothAdapter();
								// check current scan session was not stopped
								if (scanSessionId.intValue() == currentScanSession) {
									if (btAdapter.getState() == BluetoothAdapter.STATE_ON) {
										Log.i(bleManager.LOG_TAG, "[legacy] stop thread wakeup: currentScanSession [" + currentScanSession + "] scanSessionId [" + scanSessionId.intValue() + "]");
										btAdapter.stopLeScan(mLeScanCallback);
									} else {
										Log.i(bleManager.LOG_TAG, "[legacy] stop thread wakeup: currentScanSession [" + currentScanSession + "] but BT state is not ON");
									}
									WritableMap map = Arguments.createMap();
									bleManager.sendEvent("BleManagerStopScan", map);
								} else {
									Log.i(bleManager.LOG_TAG, "[legacy] stop thread wakeup but found incorrect sessionId -- ignroed");
								}
							} catch(Exception e) {
								Log.i(bleManager.LOG_TAG, "[legacy] stop thread wakeup catch an exception");
							}
						}
					}, scanSeconds * 1000);
				}
			});
		}

		callback.invoke();
	}

	/*
        BLE Scan record parsing
        inspired by:
        http://stackoverflow.com/questions/22016224/ble-obtain-uuid-encoded-in-advertising-packet
         */
	private  Map <Integer,String>  ParseRecord(byte[] scanRecord){
		Map <Integer,String> ret = new HashMap<Integer,String>();
		int index = 0;
		while (index < scanRecord.length) {
			int length = scanRecord[index++];
			//Zero value indicates that we are done with the record now
			if (length == 0) break;

			int type = scanRecord[index];
			//if the type is zero, then we are pass the significant section of the data,
			// and we are thud done
			if (type == 0) break;

			byte[] data = Arrays.copyOfRange(scanRecord, index + 1, index + length);
			if(data != null && data.length > 0) {
				// StringBuilder hex = new StringBuilder(data.length * 2);
				// // the data appears to be there backwards
				// for (int bb = data.length- 1; bb >= 0; bb--){
				// 		hex.append(String.format("%02X", data[bb]));
				// }
				// ret.put(type,hex.toString());
				ret.put(type,new String(data));
			}
			index += length;
		}

		return ret;
	}

}
