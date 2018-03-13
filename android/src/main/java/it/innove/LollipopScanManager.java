package it.innove;


import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.util.Log;
import com.facebook.react.bridge.*;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

import static com.facebook.react.bridge.UiThreadUtil.runOnUiThread;

import android.os.Handler;
import java.lang.Runnable;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class LollipopScanManager extends ScanManager {

    private List<String> matchNames = null;

	public LollipopScanManager(ReactApplicationContext reactContext, BleManager bleManager) {
		super(reactContext, bleManager);
	}

	@Override
	public void stopScan(Callback callback) {
		// update scanSessionId to prevent stopping next scan by running timeout thread
		scanSessionId.incrementAndGet();
        Log.i(bleManager.LOG_TAG, "stopScan() scanSessionId [" + scanSessionId + "]");

		getBluetoothAdapter().getBluetoothLeScanner().stopScan(mScanCallback);
		callback.invoke();
	}

    @Override
    public void scan(ReadableArray serviceUUIDs, final int scanSeconds, ReadableMap options,  Callback callback) {
        ScanSettings.Builder scanSettingsBuilder = new ScanSettings.Builder();
        List<ScanFilter> filters = new ArrayList<>();
        
        scanSettingsBuilder.setScanMode(options.getInt("scanMode"));
        
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
            scanSettingsBuilder.setNumOfMatches(options.getInt("numberOfMatches"));
            scanSettingsBuilder.setMatchMode(options.getInt("matchMode"));
        }

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
        //----------------------------------------------------------------------
        
        if (serviceUUIDs.size() > 0) {
            for(int i = 0; i < serviceUUIDs.size(); i++){
//				ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(UUIDHelper.uuidFromString(serviceUUIDs.getString(i)))).build();
                ScanFilter filter = new ScanFilter.Builder().setDeviceAddress(serviceUUIDs.getString(i)).build();
                filters.add(filter);
                Log.d(bleManager.LOG_TAG, "Filter service: " + serviceUUIDs.getString(i));
            }
        }


        //------------------------------------------------------------------------------------
        // flush before scan: does it help?
        //------------------------------------------------------------------------------------
        //getBluetoothAdapter().getBluetoothLeScanner().flushPendingScanResults(mScanCallback);
        //------------------------------------------------------------------------------------
        getBluetoothAdapter().getBluetoothLeScanner().startScan(filters, scanSettingsBuilder.build(), mScanCallback);
        //--------------------------------------------------------------------------
        // ASTRI: try using Handler.postDelayed() instead of Thread.sleep()
        // reference: https://stackoverflow.com/questions/29731176/ble-scanning-callback-only-get-called-several-times-then-stopped
        // Not yet tried whether this is better or not...
        //--------------------------------------------------------------------------
        if (scanSeconds > 0) {
            final Handler mainHandler = new Handler(this.reactContext.getMainLooper());
            mainHandler.post(new Runnable() {

                private int currentScanSession = scanSessionId.incrementAndGet();

                @Override
                public void run() {
                    Log.i(bleManager.LOG_TAG, "stop thread create: currentScanSession [" + currentScanSession + "]");
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                BluetoothAdapter btAdapter = getBluetoothAdapter();
                                // check current scan session was not stopped
                                if (scanSessionId.intValue() == currentScanSession) {
                                    if(btAdapter.getState() == BluetoothAdapter.STATE_ON) {
                                        Log.i(bleManager.LOG_TAG, "stop thread wakeup: currentScanSession [" + currentScanSession + "] scanSessionId [" + scanSessionId.intValue() + "]");
                                        btAdapter.getBluetoothLeScanner().stopScan(mScanCallback);
                                        //------------------------------------------------------------------------------------
                                        // flush after scan: does it help?
                                        //------------------------------------------------------------------------------------
                                        //btAdapter.getBluetoothLeScanner().flushPendingScanResults(mScanCallback);
                                        //------------------------------------------------------------------------------------
                                    } else {
                                        Log.i(bleManager.LOG_TAG, "stop thread wakeup: currentScanSession [" + currentScanSession + "] but BT state is not ON");
                                    }
                                    WritableMap map = Arguments.createMap();
                                    bleManager.sendEvent("BleManagerStopScan", map);
                                } else {
                                    Log.i(bleManager.LOG_TAG, "stop thread wakeup: currentScanSession [" + currentScanSession + "] but found incorrect scanSessionId -- ignroed");
                                }
                            } catch(Exception e) {
                                Log.i(bleManager.LOG_TAG, "stop thread wakeup catch an exception");
                            }
                        }
                    }, scanSeconds * 1000);
                }
            });
        }

        
//        getBluetoothAdapter().getBluetoothLeScanner().startScan(filters, scanSettingsBuilder.build(), mScanCallback);
//        if (scanSeconds > 0) {
//            Thread thread = new Thread() {
//                private int currentScanSession = scanSessionId.incrementAndGet();
//
//                @Override
//                public void run() {
//
//                    try {
//                        Thread.sleep(scanSeconds * 1000);
//                    } catch (InterruptedException ignored) {
//                    }
//
//                    runOnUiThread(new Runnable() {
//                        @Override
//                        public void run() {
//                            BluetoothAdapter btAdapter = getBluetoothAdapter();
//                            // check current scan session was not stopped
//                            if (scanSessionId.intValue() == currentScanSession) {
//                                if(btAdapter.getState() == BluetoothAdapter.STATE_ON) {
//                                    btAdapter.getBluetoothLeScanner().stopScan(mScanCallback);
//                                }
//                                WritableMap map = Arguments.createMap();
//                                bleManager.sendEvent("BleManagerStopScan", map);
//                            }
//                        }
//                    });
//
//                }
//
//            };
//            thread.start();
//        }


        callback.invoke();
    }

	private ScanCallback mScanCallback = new ScanCallback() {
		@Override
		public void onScanResult(final int callbackType, final ScanResult result) {
            Log.i(bleManager.LOG_TAG, "****** onScanResult called ******");

            //-----------------------------------------------------------------------------
            // NOTE: sometimes the local BLE adpator has cached empty (null) device name.
            // We can either:
            // (1) clear the local cache (but not yet tried),
            // 		https://stackoverflow.com/questions/10793761/how-to-programmatically-clear-the-bluetooth-name-cache-in-android
            // (2) or do a hotfix here using the
            // 		result.getScanRecord().getDeviceName() which is not cached.
            // Note that it has nothing to do with our bleManager.peripherals, because that one store
            // the result.getDevice(), where the local BLE adapter will return the cached name.
            //-----------------------------------------------------------------------------

			runOnUiThread(new Runnable() {
				@Override
				public void run() {
                    //-----------------------------------------------------------------
                    String localName = result.getScanRecord().getDeviceName(); // Hotfix
                    //-----------------------------------------------------------------
					String address = result.getDevice().getAddress();
                    Peripheral peripheral = null;

                    //------------------------------------------------------------------------------------
                    // filter out not valid device
                    //------------------------------------------------------------------------------------
                    if (localName == null || localName.isEmpty() || address == null ||  address.isEmpty())
                    {
                        Log.i(bleManager.LOG_TAG, "DiscoverPeripheral (ignored, invalid device): [" + result.getDevice().getName() + "] localName=[" + localName + "] address:[" + address + "]");
                        return;
                    }
                    //------------------------------------------------------------------------------------
                    // filter by match names
                    //------------------------------------------------------------------------------------
                    if(matchNames!=null && !matchNames.contains(localName)){
                        Log.i(bleManager.LOG_TAG, "DiscoverPeripheral (ignored, mismatch name): [" + result.getDevice().getName() + "] localName=[" + localName + "] address:[" + address + "]");
                        return;
                    }


                    Log.i(bleManager.LOG_TAG, "DiscoverPeripheral (*******): [" + result.getDevice().getName() + "] localName=[" + localName + "] address:[" + address + "]");
					if (!bleManager.peripherals.containsKey(address)) {
						peripheral = new Peripheral(result.getDevice(), result.getRssi(), result.getScanRecord().getBytes(), reactContext);
						bleManager.peripherals.put(address, peripheral);
					} else {
						peripheral = bleManager.peripherals.get(address);
						peripheral.updateRssi(result.getRssi());
						peripheral.updateData(result.getScanRecord().getBytes());
					}

					WritableMap map = peripheral.asWritableMap();
                    map.putString("name", localName);
					bleManager.sendEvent("BleManagerDiscoverPeripheral", map);
				}
			});
		}

		@Override
		public void onBatchScanResults(final List<ScanResult> results) {
		}

		@Override
		public void onScanFailed(final int errorCode) {
            WritableMap map = Arguments.createMap();
            bleManager.sendEvent("BleManagerStopScan", map);
		}
	};
}
