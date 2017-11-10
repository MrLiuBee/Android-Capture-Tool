package com.sprintwind.packetcapturetool;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.lang.Process;
//import sun.misc.Signal;

import com.sprintwind.packetcapturetool.ShellUtils.CommandResult;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;


@TargetApi(Build.VERSION_CODES.HONEYCOMB) public class CaptureActivity extends Fragment {
	
	private static int BUFFER_SIZE = 1024;
	private final int MAX_CAPSIZE_LEN = 5;
	private final int MAX_SAVE_FILE_NAME_LEN = 32;
	private final int MIN_CAP_SIZE = 24;
	private final int MAX_CAP_SIZE = 65535;
	private final int MAX_INTEGER_LEN = 10;
	private final int THREAD_SLEEP_TIME = 200;
	
	
	private final String CAP_TOOL = "cap_tool";
	private final String BUSYBOX = "busybox";
	private final String PCAP_FILE_SUFFIX = ".pcap";
	private final String STATS_FILE = ".cap_stats";
	private final String CAP_FILE_DIR = "packet_capture";
	private final String LOG_TAG = "sprintwind";
	
	private final String ITEM_IMAGE = "ItemImage";
	private final String ITEM_TITLE = "ItemTitle";
	private final String ITEM_TEXT  = "ItemText";
	
	private enum ErrorCode{
		OK,
		ERR_FILE_NOT_EXIST,
		ERR_DEL_FILE_FAILED,
		ERR_IO_EXCEPTION,
	};
	
	private enum ListItemCol{
		COL_IFNAME,
		COL_PROTOCOL
	};
	
	private enum CaptureStatus{
		STATUS_IDLE,
		STATUS_STARTING,
		STATUS_CAPTURING,
		STATUS_STOPPING
	};

	private ArrayAdapter<String> arradpInterface;
	private ArrayAdapter<CharSequence> arradpProtocol;
	private ConnectionChangeReceiver broadcastReceiver;
	
	
	private Button btnStartCapture;
	private EditText etFileName;
	private Button btnReGenerateFileName;
	private ListView lvSettings;
	
	private Dialog	dlgCapturing;
	private TextView txtvwCaptureAmount;
	private Button btnStopCapture;
	
	private ArrayList<HashMap<String, Object>> lstItems;
	private Handler handler;
	private Process process;
	private String  captoolDir;
	private String busyboxDir = "/sdcard";
	private String sdcardDir;
	private String saveFilePath;
	private String[] strArrIfName;
	
	private CommandResult cmdResult;
	private int result;
	private String capture_stats;
	private String last_capture_stats;
	private CaptureStatus enCaptureStatus = CaptureStatus.STATUS_IDLE;
	private boolean capture = false;
	private int updateCount = 0;
	
    
    /*
     * 初始化CaptureView
     */
	private void initCaptureView(View view)
    {		 
    	btnStartCapture = (Button) view.findViewById(R.id.btnStartCapture);
		btnStartCapture.setOnClickListener((OnClickListener) new OnBtnStartCaptureClickListener());
		
		etFileName = (EditText) view.findViewById(R.id.etFileName);
		etFileName.setMaxWidth(MAX_SAVE_FILE_NAME_LEN);
		etFileName.setText(generateFileNameByNowTime());
		
		btnReGenerateFileName = (Button)view.findViewById(R.id.btnReGenerateFileName);
		btnReGenerateFileName.setOnClickListener(new OnBtnGenerateFileNameClickListener());
		
		handler = new Handler();
		
		/* 初始化相关路径 */
		captoolDir = this.getActivity().getApplicationContext().getFilesDir().getParentFile().getPath();

		sdcardDir= android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
		System.out.println("the sdcardDir is: " + sdcardDir);
		
		/* 创建应用目录 */
		createAppDirectory();

		/* 注册网络变化通知 */
		registerNetStateReceiver();
		
		/* 加载网口信息 */
		loadInterface();
		
		lvSettings = (ListView) view.findViewById(R.id.lstVwSettings);
		lstItems = new ArrayList<HashMap<String, Object>>();
		
		/* 网口名称 */
		HashMap<String, Object> hmIfName = new HashMap<String, Object>();
		hmIfName.put(ITEM_IMAGE, R.drawable.goto_icon_selecter);
		hmIfName.put(ITEM_TITLE, getString(R.string.chose_interface));
		hmIfName.put(ITEM_TEXT, strArrIfName[0]); //以第一个元素作为初值
		lstItems.add(hmIfName);
		
		/* 协议类型 */
		HashMap<String, Object> hmProtocol = new HashMap<String, Object>();
		hmProtocol.put(ITEM_IMAGE, R.drawable.goto_icon_selecter);
		hmProtocol.put(ITEM_TITLE, getString(R.string.choose_protocol));
		hmProtocol.put(ITEM_TEXT, getResources().getStringArray(R.array.protocol)[0]); //以第一个元素作为初值
		lstItems.add(hmProtocol);
		
		updateListSettings();
		lvSettings.setOnItemClickListener(new OnSettingsItemClickListener());
    }
	
	@Override  
    public View onCreateView(LayoutInflater inflater, ViewGroup container,  
            Bundle savedInstanceState) {
		
		View view = inflater.inflate(R.layout.activity_capture, container, false); 
		initCaptureView(view);
		
		return view;
	}

	
	/*
     * 加载网口列表
     */
    public void loadInterface()
    {
    	String interfaceStr = JNIgetInterfaces();
		if(null != interfaceStr)
		{
			interfaceStr = getString(R.string.all_interfaces)+"|"+interfaceStr;
			Log.i(LOG_TAG, "interfaceStr:"+interfaceStr);
			strArrIfName = interfaceStr.split("\\|");
			
			//Toast.makeText(getApplicationContext(), "网口信息加载完成", Toast.LENGTH_SHORT).show();
		}
		else{
			Log.e(LOG_TAG, "interfaceArray is null!");
			strArrIfName = new String[]{getString(R.string.all_interfaces)};
			Toast.makeText(this.getActivity().getApplicationContext(), "没有可用网口信息！", Toast.LENGTH_SHORT).show();
		}
    }
    
    /* 
     * 更新设置列表
     */
    public void updateListSettings()
    {
    	String[] strFrom = new String[] {ITEM_IMAGE,ITEM_TITLE, ITEM_TEXT};
		int[] iTo = new int[] {R.id.ItemImage, R.id.ItemTitle, R.id.ItemText};
		SimpleAdapter sadpSettings = new SimpleAdapter(this.getActivity(), lstItems, R.layout.capture_settings_listview_item, strFrom, iTo);
		lvSettings.setAdapter(sadpSettings);
    }
    
    /*
     * 按当前的时间生成文件名
     */
    public String generateFileNameByNowTime()
    {
    	SimpleDateFormat format = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.CHINA);
		return format.format(new Date());
    }
    
    /*
     * 创建应用目录
     */
    public void createAppDirectory()
    {
    	
    	 boolean sdCardExist = Environment.getExternalStorageState().equals(android.os.Environment.MEDIA_MOUNTED);
    	 if(!sdCardExist)
    	 {
    		 //Toast.makeText(this, "请插入外部SD存储卡", Toast.LENGTH_SHORT).show();
    		 Log.e(LOG_TAG, "SD card absent, will not create application directory!");
    		 return;
    	 }
    	 
    	 File appDir = new File(sdcardDir+"/"+CAP_FILE_DIR);
    	 File appDir1 = new File(sdcardDir+"/"+"i_record");
    	 if(!appDir.exists())
    	 {
    		 appDir.mkdir();
    	 }
    	 if(!appDir1.exists())
    	 {
    		 appDir1.mkdir();
    	 }
    	 return;
    }
    
    /*
     * 检查输入的抓包大小合法性
     */
    public boolean isValidCaptureSize(int length)
    {
    	
    	if( (length < MIN_CAP_SIZE)||(length > MAX_CAP_SIZE))
    	{
    		return false;
    	}
    	
    	return true;
    }
    
    /*
     * 开始抓包
     */
    public void startCapture()
    {
    	/* 启动抓包线程 */
		CaptureThread captureThread = new CaptureThread();
		Thread thread = new Thread(captureThread);
		thread.start();
		/*扫描系统流量文件*/
		//JNItcpinfo();
    }
    
    public class OnInterfaceItemClickListener implements android.content.DialogInterface.OnClickListener{

		@Override
		public void onClick(DialogInterface arg0, int arg1) {
			// TODO Auto-generated method stub
			HashMap<String, Object> hmIfName = lstItems.get(ListItemCol.COL_IFNAME.ordinal());
			hmIfName.remove(ITEM_TEXT);
			hmIfName.put(ITEM_TEXT, strArrIfName[arg1]);
			
			lstItems.set(ListItemCol.COL_IFNAME.ordinal(), hmIfName);
			
			updateListSettings();
			arg0.dismiss();
		}
    	
    }
    
    public class OnProtocolItemClickListener implements android.content.DialogInterface.OnClickListener{

		@Override
		public void onClick(DialogInterface arg0, int arg1) {
			// TODO Auto-generated method stub
			HashMap<String, Object> hmProtocol = lstItems.get(ListItemCol.COL_PROTOCOL.ordinal());
			hmProtocol.remove(ITEM_TEXT);
			hmProtocol.put(ITEM_TEXT, getResources().getStringArray(R.array.protocol)[arg1]);
			
			lstItems.set(ListItemCol.COL_PROTOCOL.ordinal(), hmProtocol);
			
			updateListSettings();
			arg0.dismiss();
		}
    	
    }
    
    public class OnSettingsItemClickListener implements OnItemClickListener{

		@Override
		public void onItemClick(AdapterView<?> adapter, View view, int col,
				long arg3) {
			// TODO Auto-generated method stub
			
			AlertDialog.Builder dialog = new AlertDialog.Builder(CaptureActivity.this.getActivity());
			
			/* 点击了网口名称时弹出选择网口名称对话框 */
			if(col == ListItemCol.COL_IFNAME.ordinal())
			{
				dialog.setTitle(R.string.chose_interface);
				dialog.setSingleChoiceItems(strArrIfName, -1, new OnInterfaceItemClickListener());
				//dialog.setCancelable(false);
				dialog.show();
				return;
			}
			
			/* 点击了协议类型时弹出协议类型选择对话框 */
			if(col == ListItemCol.COL_PROTOCOL.ordinal())
			{
				dialog.setTitle(R.string.choose_protocol);
				dialog.setSingleChoiceItems(R.array.protocol, -1, new OnProtocolItemClickListener());
				//dialog.setCancelable(false);
				dialog.show();
				return;
			}
			
		}
    	
    }
    
    /*
     * 获取ListView空间的元素内容
     */
    public String getItemNameOfListView(ListItemCol col)
    {
    	HashMap<String, Object> hmItem = lstItems.get(col.ordinal());
		return (String) hmItem.get(ITEM_TEXT);
    }
    
    public class OnBtnStopCaptureClickListener implements OnClickListener{

		@Override
		public void onClick(View arg0) {
			// TODO Auto-generated method stub
			enCaptureStatus = CaptureStatus.STATUS_STOPPING;
			
			//getRootPermission();
			String[] commands = {
					//"chmod 755 "+captoolDir+"/"+CAP_TOOL,
					"/system/xbin/busybox killall -SIGINT "+captoolDir+"/"+CAP_TOOL //会发生进程死锁
					//"busybox killall -SIGINT "+captoolDir+"/"+CAP_TOOL
					//"busybox killall -SIGKILL "+CAP_TOOL
			};		
			try {
				result = up_permission(commands);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			/*cmdResult = null;
		    cmdResult = ShellUtils.execCommand(commands, true, true);*/
			System.out.println("--------ffffffffff\n");
		    handler.post(new UpdateThread());
			Log.i("sprintwind","UpdateThread posted");
		    
		    capture_stats = null;
			//dlgCapturing.dismiss();
			
			//enCaptureStatus = CaptureStatus.STATUS_IDLE;
			//handler.post(new UpdateThread());
		}
    	
    }
    
    public class OnBtnStartCaptureClickListener implements OnClickListener{

		@Override
		public void onClick(View arg0) {
			// TODO Auto-generated method stub
			
				/* 检查手机是否已经获得root权限 */
				if(!is_root())
				{
					Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), getString(R.string.no_root_permission), Toast.LENGTH_SHORT).show();
					Log.e(LOG_TAG, "the cellphone has no root permision");
					return;
				}
				else
				{
					Log.e(LOG_TAG,"the cellphone has rooted");
				}
			
				/* 检查内存卡是否存在 */
				if( (sdcardDir == null)||!isFileExist(sdcardDir) )
				{
					Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), getString(R.string.sdcard_not_exist), Toast.LENGTH_SHORT).show();
					Log.e(LOG_TAG, "SD card does not exist");
					return;
				}
				
				createAppDirectory();
				
				/* 检查网口是否已选择 */
				if(getItemNameOfListView(ListItemCol.COL_IFNAME).equals(getString(R.string.click_to_chose)+getString(R.string.chose_interface)))
				{
					Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), getString(R.string.havent_chose)+getString(R.string.chose_interface), Toast.LENGTH_SHORT).show();
					return;
				}
				
				/* 检查协议类型是否已选择 */
				if(getItemNameOfListView(ListItemCol.COL_PROTOCOL).equals(getString(R.string.click_to_chose)+getString(R.string.choose_protocol)))
				{
					Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), getString(R.string.havent_chose)+getString(R.string.choose_protocol), Toast.LENGTH_SHORT).show();
					return;
				}
				
				String strFileName = etFileName.getText().toString();
				/* 文件名不能为空 */
				if(strFileName.length() == 0)
				{
					Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), getString(R.string.empty_file_name), Toast.LENGTH_SHORT).show();
					return;
				}
				
				/* 文件名不能包含^，/，\,<,>,*,?,|等特殊字符 */
				if(!strFileName.matches("[^/\\\\<>*?|\"]+"))
				{
					Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), getString(R.string.invalid_file_name), Toast.LENGTH_SHORT).show();
					return;
				}
				
				String[] commands2 = {
						//"su",
						"mount -o remount,rw /system",
						"chmod 777 /system"
				};
				//cmdResult = null;
				//cmdResult = ShellUtils.execCommand(commands2, true, true);
				
				try {
					up_permission(commands2);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				/* 拷贝抓包工具到应用安装目录 */
				if(ErrorCode.OK !=moveRawFileToAppPath(R.raw.cap_tool, captoolDir))
				{
					return;
				}
				System.out.println("the cap_tool path is: "+captoolDir);
	
				/* 拷贝busybox工具到指定目录/system/xbin */
				if(ErrorCode.OK !=moveRawFileToAppPath1(R.raw.busybox, busyboxDir))
				{
					return;
				}
				
				String[] commands1 = {
						"mv /sdcard/busybox /system/xbin"
						//busyboxDir+"/"+BUSYBOX + " " 
						//+ "--install"//interface
						//+ " " + "-s"
						//+ " " + "/system/xbin" 
				};
				cmdResult = null;
				cmdResult = ShellUtils.execCommand(commands1, true, true);
				
				/* 生成抓包文件保存路径 */
				saveFilePath = sdcardDir + "/" +CAP_FILE_DIR+"/" + etFileName.getText().toString() + PCAP_FILE_SUFFIX;
				
				
				/* 检查是否有重名文件，有则提示是否覆盖 */
				if(isFileExist(saveFilePath))
				{
					AlertDialog.Builder builder = new AlertDialog.Builder(CaptureActivity.this.getActivity());
					builder.setTitle(getString(R.string.file_exits));
					builder.setPositiveButton("是", new DialogInterface.OnClickListener() {  
				           public void onClick(DialogInterface dialog, int id) {  
				        	   startCapture();
				        	   dialog.dismiss();
				           }
				       });
					builder.setNegativeButton("否", new DialogInterface.OnClickListener() {  
				           public void onClick(DialogInterface dialog, int id) {  
				        	   dialog.cancel();
				           }  
				       });
					AlertDialog alert = builder.create();
					alert.show();
					
				}
				else{
					startCapture();
				}
		}
		
	}
    
    public class OnBtnGenerateFileNameClickListener implements OnClickListener{

		@Override
		public void onClick(View arg0) {
			// TODO Auto-generated method stub
			etFileName.setText(generateFileNameByNowTime());
		}
    	
    }
    
    /*
     * 抓包线程
     */
    public class CaptureThread implements Runnable{

		@Override
		public void run() {
			// TODO Auto-generated method stub
			enCaptureStatus = CaptureStatus.STATUS_STARTING;
			handler.post(new UpdateThread());
			
			String strIfName = getItemNameOfListView(ListItemCol.COL_IFNAME);
			/* 当选择所有网口时，将接口名赋值为"all", JNI将不绑定网口 */
			if(strIfName.equals(getString(R.string.all_interfaces)))
			{
				strIfName = "all";
			}
			
			int strProName = JNIgetProtoValue(getItemNameOfListView(ListItemCol.COL_PROTOCOL));
			Log.e(LOG_TAG, "the protocol value is:"+strProName);
			Log.i(LOG_TAG, "captoolDir:"+captoolDir);
			Log.i(LOG_TAG, "sdcardDir:"+sdcardDir);
			/*if(!getRootPermission())
			{
				Log.i("sprintwind", "get the root permission failed");
			}else{
				Log.i("sprintwind", "get the root permission success");
			}
			
			ShellUtils.execCommand("su",true,true);
			
			int startcap = JNIstartCapture(strIfName,strProName,65535);	
			Log.e(LOG_TAG, "the return startcap:"+startcap);
			if(startcap != 0)
			{
				Log.i("sprintwind", "start capture failed");
				Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), getString(R.string.start_capture_failed), Toast.LENGTH_LONG).show();
				return;
			}*/
			
			
			
			//usage:CAP_TOOL <dev> <protocol> <cap_len> <save_path> <file_name>
			String[] commands = {
					"chmod 755 "+captoolDir+"/"+CAP_TOOL,
					captoolDir+"/"+CAP_TOOL + " " 
					+ strIfName//interface
					+ " " + getItemNameOfListView(ListItemCol.COL_PROTOCOL)//protocol
					+ " " + "262144"//etCapSize.getText() //capture size
					+ " " + sdcardDir + "/"+CAP_FILE_DIR //save dir
					+ " " + etFileName.getText().toString() 
			};
			
			
			cmdResult = null;
			cmdResult = ShellUtils.execCommand(commands, true, true);
		
		}
		
	}
    
    /*
     * 统计线程
     */
    public class StatisticThread implements Runnable{

		@Override
		public void run() {
			// TODO Auto-generated method stub
			
			while(enCaptureStatus == CaptureStatus.STATUS_CAPTURING)
			{
				try {
					File file = new File(sdcardDir+"/" +CAP_FILE_DIR+"/"+STATS_FILE);
					if(!file.exists())
					{
						continue;
					}
					BufferedReader br = new BufferedReader(new FileReader(file));
					capture_stats = br.readLine();
					Log.i(LOG_TAG, "stats:"+capture_stats);
					//capture_count = Integer.parseInt(stats);
					br.close();
					handler.post(new UpdateThread());
					Thread.sleep(1000);
					++updateCount;
				} catch (FileNotFoundException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			
			capture_stats = null;
		}
    	
    }
	
    /*
     * 更新UI线程
     */
	public class UpdateThread implements Runnable{
		
		@Override
		public void run() {
			// TODO Auto-generated method stub
			
			switch(enCaptureStatus)
			{
				case STATUS_IDLE:
					btnStartCapture.setText(getString(R.string.start_capture));
					break;
				case STATUS_STARTING:
					btnStartCapture.setText(getString(R.string.starting_capture));
					
					/* sleep,等待命令执行结果 */
					try {
						Thread.sleep(THREAD_SLEEP_TIME, 0);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					
					if(null!=cmdResult && cmdResult.result < 0)
					{
						Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), getString(R.string.capture_failed), Toast.LENGTH_LONG).show();
						Log.e(LOG_TAG, "start capture failed, error msg:"+cmdResult.errorMsg);
						btnStartCapture.setText(getString(R.string.start_capture));
						break;
					}
					
					/* 没有返回失败说明抓包启动成功 */
					showCapturingDialog();
					break;
				case STATUS_CAPTURING:
					btnStartCapture.setText(getString(R.string.capturing));
					if(null != capture_stats)
					{
						updateCaptureStatistics();
					}
					break;
				case STATUS_STOPPING:
					btnStopCapture.setText(getString(R.string.stopping_capture));
					Log.i(LOG_TAG, "stopping...");
					
					/*try {
						Thread.sleep(THREAD_SLEEP_TIME, 0);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}*/
					
					if(result != 0)
					{
						Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), getString(R.string.stop_capture_failed), Toast.LENGTH_LONG).show();
						Log.e(LOG_TAG, "stop capture failed, error msg:"+cmdResult.errorMsg);
					}
					else
					{
						Log.i("sprintwind", "stop success");
						Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), getString(R.string.capture_stopped)+saveFilePath, Toast.LENGTH_LONG).show();
					}
					
					/* 无论如何，都要回到空闲状态 */
					enCaptureStatus = CaptureStatus.STATUS_IDLE;
					handler.post(new UpdateThread());
					
					cmdResult = null;
					dlgCapturing.dismiss();
					
					break;
					
				default:
					break;
				
			}
			
		}
		
	}
	
	/*
	 * 更新抓包统计信息
	 */
	public void updateCaptureStatistics()
	{
		//tvStatus.setText("已抓取："+capture_stats);
		String strText = "已抓取";
		int start = strText.length();
		strText += capture_stats;
		int end = strText.length();
		strText += "个报文";
		SpannableStringBuilder style=new SpannableStringBuilder(strText);
		
		/* 抓包个数每隔一秒闪烁一次，遇到有变化时也要闪烁 */
		if((updateCount%2 == 0)||(!capture_stats.equals(last_capture_stats)))
		{
			style.setSpan(new BackgroundColorSpan(Color.parseColor("#ADD8E6")),start,end,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		
		last_capture_stats = capture_stats;
		
		txtvwCaptureAmount.setText(style);
	}
	
	/*
	 * 显示正在抓包对话框
	 */
	public void showCapturingDialog()
	{
		/* 创建新的对话框显示抓包状态 */
		dlgCapturing = new Dialog(CaptureActivity.this.getActivity());
		dlgCapturing.setContentView(R.layout.capture_dialog);
		dlgCapturing.setTitle(R.string.capturing);
		dlgCapturing.setCancelable(false);
	    
	    btnStopCapture = (Button)dlgCapturing.findViewById(R.id.btnStopCapture);
	    btnStopCapture.setText(R.string.stop_capture);
	    btnStopCapture.setOnClickListener(new OnBtnStopCaptureClickListener());
	    
	    txtvwCaptureAmount = (TextView)dlgCapturing.findViewById(R.id.txtvwCaptureAmount);
	    
	    /* 启动统计线程 */
	    enCaptureStatus = CaptureStatus.STATUS_CAPTURING;
	    new Thread(new StatisticThread()).start();
	    
	    dlgCapturing.show();
	}
	
	/*
	 * get root permission
	 */
	public boolean getRootPermission()
	{
		CommandResult result = ShellUtils.execCommand("chmod "+"777 "+captoolDir, true, true);
		if(result.result == 0){
			return true;
		}
		else{
			return false;
		}
	}
	
	/* 
	 * 判断设备是否已经具有root权限
	 */
	public static boolean is_root(){

	    boolean res = false;

	    try{ 
	    	if ((!new File("/system/bin/su").exists()) && 
	    			(!new File("/system/xbin/su").exists())){
	    		res = false;
	    	} 
	    	else {
	    		res = true;
	    	};
	    } 
	    catch (Exception e) {  

	    } 
	    return res;
	}
	
	/*
	 * 执行"su"命令，进一步提权 
	 */
	public static int up_permission(String[] strCommand) throws IOException, InterruptedException{
		//权限设置,需要root权限为"su"，否则为"sh";
        Process process = Runtime.getRuntime().exec("su");
        //获取输出流
        DataOutputStream dataOutputStream=new DataOutputStream(process.getOutputStream());
        //将命令写入
        for (int i = 0; i < strCommand.length; i++){
        	dataOutputStream.write(strCommand[i].getBytes());
            dataOutputStream.writeBytes("\n");
        }
        dataOutputStream.writeBytes("exit\n");
        //提交命令
        dataOutputStream.flush();
        
        //获取执行结果状态码
        int result = process.waitFor();
        
        //读取执行信息
        StringBuilder successMsg = new StringBuilder();
        StringBuilder errorMsg = new StringBuilder();
        BufferedReader successResult = new BufferedReader(new InputStreamReader(process.getInputStream()));
        BufferedReader errorResult = new BufferedReader(new InputStreamReader(process.getErrorStream()));
        String s;
        while ((s = successResult.readLine()) != null) {
                successMsg.append(s);
        }
       while ((s = errorResult.readLine()) != null) {
                errorMsg.append(s);
       }
       
        //关闭流操作
        dataOutputStream.close();
        successResult.close();
        errorResult.close();
        /*if (result == 0)
        	return successMsg.toString();
        else
        	return errorMsg.toString();*/
        return result;
	}
	
	/*
	 * 判断文件是否存在
	 */
	public boolean isFileExist(String filePath)
	{
		File file = new File(filePath);
	   	 if(file.exists())
	   	 {
	   		 return true;
	   	 }
	   	 
	   	 return false;
	}
	
    /**
     * 删除单个文件
     * @param   sPath    被删除文件的文件名
     * @return 单个文件删除成功返回true，否则返回false
     */
    public boolean deleteFile(String sPath) {
        File file = new File(sPath);
        // 路径为文件且不为空则进行删除
        if (file.isFile() && file.exists()) {
            return file.delete();
        }
        
        return true;
    }
	
	/*
	 * 拷贝安装包中文件到指定的路径，文件已存在则尝试覆盖
	 */
	public ErrorCode moveRawFileToAppPath(int rawFileId, String dstPath)
	{
		/* 检测路径是否有效 */
		File dstDir = new File(dstPath);
		if(!dstDir.exists()){
			Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), "目录"+dstDir+"不存在", Toast.LENGTH_SHORT).show();
			Log.e(LOG_TAG, "目录"+dstDir+"不存在");
			return ErrorCode.ERR_FILE_NOT_EXIST;
		}
		
		/* 删除原有文件 */
		File file = new File(dstPath+"/"+CAP_TOOL);
		if(file.exists())
		{
			if(!file.delete())
			{
				Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), "删除"+dstDir+"原有文件失败，请重启手机后重试", Toast.LENGTH_SHORT).show();
				Log.e(LOG_TAG, "删除"+dstDir+"文件失败");
				return ErrorCode.ERR_DEL_FILE_FAILED;
			}
		}
		
		InputStream is = getResources().openRawResource(rawFileId);
		try {
			FileOutputStream fos = new FileOutputStream(dstPath+"/"+CAP_TOOL);
			
			byte[] buffer = new byte[1024];
			int count = 0;
			try {
				while((count = is.read(buffer)) > 0){
					fos.write(buffer, 0, count);
				}
				fos.close();
				is.close();
				
			} catch (IOException e) {
				// TODO Auto-generated catch block
				Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), "内部异常!"+e.getMessage(), Toast.LENGTH_SHORT).show();
				e.printStackTrace();
				return ErrorCode.ERR_IO_EXCEPTION;
			}
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), "文件不存在!"+e.getMessage(), Toast.LENGTH_SHORT).show();
			e.printStackTrace();
			return ErrorCode.ERR_FILE_NOT_EXIST;
		}
		
		Log.i(LOG_TAG, "copy raw file "+rawFileId+" to "+dstPath+" success");
		return ErrorCode.OK;
	}
	
	/*
	 * 拷贝安装包中文件busybox到路径/system/xbin，文件已存在则尝试覆盖
	 */
	public ErrorCode moveRawFileToAppPath1(int rawFileId, String dstPath)
	{
		/* 检测路径是否有效 */
		File dstDir = new File(dstPath);
		if(!dstDir.exists()){
			Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), "目录"+dstDir+"不存在", Toast.LENGTH_SHORT).show();
			Log.e(LOG_TAG, "目录"+dstDir+"不存在");
			return ErrorCode.ERR_FILE_NOT_EXIST;
		}
		
		/* 删除原有文件 */
		File file = new File(dstPath+"/"+BUSYBOX);
		if(file.exists())
		{
			//return ErrorCode.OK;
			if(!file.delete())
			{
				Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), "删除"+dstDir+"原有文件失败，请重启手机后重试", Toast.LENGTH_SHORT).show();
				Log.e(LOG_TAG, "删除"+dstDir+"文件失败");
				return ErrorCode.ERR_DEL_FILE_FAILED;
			}
		}
		
		InputStream is = getResources().openRawResource(rawFileId);
		try {
			FileOutputStream fos = new FileOutputStream(dstPath+"/"+BUSYBOX);
			
			byte[] buffer = new byte[1024];
			int count = 0;
			try {
				while((count = is.read(buffer)) > 0){
					fos.write(buffer, 0, count);
				}
				fos.close();
				is.close();
				
			} catch (IOException e) {
				// TODO Auto-generated catch block
				Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), "内部异常!"+e.getMessage(), Toast.LENGTH_SHORT).show();
				e.printStackTrace();
				return ErrorCode.ERR_IO_EXCEPTION;
			}
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			Toast.makeText(CaptureActivity.this.getActivity().getApplicationContext(), "文件不存在!"+e.getMessage(), Toast.LENGTH_SHORT).show();
			e.printStackTrace();
			return ErrorCode.ERR_FILE_NOT_EXIST;
		}
		
		Log.i(LOG_TAG, "copy raw file "+rawFileId+" to "+dstPath+" success");
		return ErrorCode.OK;
	}
	
	/*
	 * 手动注册网络状态变化。
	 */
	private void registerNetStateReceiver() {
		broadcastReceiver = new ConnectionChangeReceiver();
		IntentFilter filter = new IntentFilter();
		filter.addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
		CaptureActivity.this.getActivity().registerReceiver(broadcastReceiver, filter);
	}
	
	/*
	 * 处理网络状态变化的内部类
	 */
	public class ConnectionChangeReceiver extends BroadcastReceiver {


		@Override
		public void onReceive(Context context, Intent intent) {
			//boolean isConnected = false;

			ConnectivityManager connectivityManager = (ConnectivityManager) context
					.getSystemService(Context.CONNECTIVITY_SERVICE);

			NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();

			if (networkInfo != null) {
				if(networkInfo.isConnected())
				{
					//isConnected = true;
				}
				
				Log.i(LOG_TAG, "--Network Type  = " + networkInfo.getTypeName());
				Log.i(LOG_TAG, "--Network SubType  = " + networkInfo.getSubtypeName());
				Log.i(LOG_TAG, "--Network State = " + networkInfo.getState());
				
			} 
			
			//String networkStatus = isConnected?"连接":"断开";
			//Toast.makeText(getApplicationContext(), "网络连接已"+networkStatus+",重新加载网口信息...", Toast.LENGTH_SHORT).show();
			
			/* 网络连接发生变化后重新加载网卡信息 */
			loadInterface();
			
		}
	}
	
	@Override
	public void onDestroy() {
		CaptureActivity.this.getActivity().unregisterReceiver(broadcastReceiver);
		super.onDestroy();
	}
	
	//public native int JNIexcuteCommand(String cmd,String args);
	public native int JNIgetProtoValue(String proto);
	public native int JNIstartCapture(String dev,int proto,int cap_len);
	public native String JNIgetInterfaces();
	
	//public native int JNItcpinfo();

	static{
		System.loadLibrary("PacketCaptureTool");
		//System.loadLibrary("PortMapping");
	}
}
