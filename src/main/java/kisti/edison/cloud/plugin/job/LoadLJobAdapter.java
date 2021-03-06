package kisti.edison.cloud.plugin.job;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import kisti.edison.cloud.env.Cloud;
import kisti.edison.cloud.model.Cluster;
import kisti.edison.cloud.model.Job;
import kisti.edison.cloud.model.User;
import kisti.edison.cloud.plugin.spec.JobAdapter;

@Component("LoadL")
public class LoadLJobAdapter implements JobAdapter {
	protected final Logger LOG = Logger.getLogger(this.getClass());
	
	private Job.JobState lcmState2State(String lcmState, String exitStatus) {
		if (lcmState.equals("Done")) {
			if (exitStatus.equals("0"))
				return Job.JobState.SUCCESS;
			else
				return Job.JobState.FAILED;
		} else if (lcmState.equals("Pending")) {
			return Job.JobState.QUEUED;
		} else if (lcmState.equals("Running")) {
			return Job.JobState.RUNNING;
		} else if (lcmState.equals("Canceled")) {
			return Job.JobState.CANCELED;
		} else {
			return Job.JobState.UNKNOWN;
		}
	}

	private String makeJobScriptFile(User user, Cluster cluster, Job job) {
		String filePath = job.getWorkingDir() + "/saga-python-loadl-script";
		try {
			BufferedWriter out = new BufferedWriter(new FileWriter(filePath));
			String s = "#!/usr/bin/env python";
			out.write(s);
			out.newLine();
			
			s = "#-*- coding: UTF-8 -*-";
			out.write(s);
			out.newLine();
			
			/*
			 * os.environ for saga-Python debugging
			 */
			s = "import os";
			out.write(s);
			out.newLine();
			
//			s = "os.environ['SAGA_VERBOSE'] = 'DEBUG'";
//			out.write(s);
//			out.newLine();
//			s = "os.environ['SAGA_LOG_TARGETS'] = '" + job.getWorkingDir() + "error.log'";
//			out.write(s);
//			out.newLine();
			
			s = "import saga";
			out.write(s);
			out.newLine();
			// need variable for REMOTE_HOST
			s = "REMOTE_HOST = '" + cluster.getIp() + "'";
			out.write(s);
			out.newLine();
			s = "ctx = saga.Context('ssh')";
			out.write(s);
			out.newLine();
			s = "ctx.user_id = '" + job.getLocalAccount() + "'";
			out.write(s);
			out.newLine();
			s = "session = saga.Session()";
			out.write(s);
			out.newLine();
			s = "session.add_context(ctx)";
			out.write(s);
			out.newLine();
			
			s = "script = '''";
			out.write(s);
			out.newLine();
	
			s = "cd " + job.getWorkingDir();
			out.write(s);
			out.newLine();
			
			// Dependencies Setting
			if (job.getDependencies() != null) {
				Iterator<String> iter = job.getDependencies().keySet().iterator();
				while (iter.hasNext()) {
					String linkName = iter.next();
					String target = job.getDependencies().get(linkName);

					s = "ln -sf " + target + " " + linkName;
					out.write(s);
					out.newLine();
				}
			}
			
			s = "cp " + job.getExecutable() + " .";
			out.write(s);
			out.newLine();
			
			/* Job Command Replacements */
			String jobCommand = job.getExecution();
			if(jobCommand.contains("REDIRECTION_STDIN")) {
				jobCommand = jobCommand.replaceAll("REDIRECTION_STDIN", "<");
			}
			
			if(jobCommand.contains("REDIRECTION_STDOUT")) {
				jobCommand = jobCommand.replaceAll("REDIRECTION_STDOUT", ">");
			}
			
			if(jobCommand.contains("REDIRECTION_STDERR")) {
				jobCommand = jobCommand.replaceAll("REDIRECTION_STDERR", "2>");
			}
			
			if(jobCommand.contains("PIPE_LINE")) {
				jobCommand = jobCommand.replaceAll("PIPE_LINE", "|");
			}
			
			String executableName = job.getExecutable().substring(job.getExecutable().lastIndexOf('/')+1);
			if (job.getType().equals(Job.JobType.SEQUENTIAL)) {
				s = "./" + executableName + " " + jobCommand;
				out.write(s);
			} else {
				if (job.getCategory().equals(Job.JobCategory.INTEL_MPICH_1)) {
					/*
					 * mpirun 좀비 process 문제 해결 by 최찬호
					 */
					s = "export MPICH_PROCESS_GROUP=no";
					out.write(s);
					out.newLine();

					s = Cloud.getInstance().getProp("mpirun.path") + "/bin/mpirun"
//					s = "/opt/mpi/intel/openmpi-1.4.3/bin" + "/mpirun"
						+ " -machinefile " + "$LOADL_HOSTFILE" + " -np "
						+ job.getnProcs() + " ./" + executableName + " "
						+ jobCommand;
					out.write(s);
				} else if(job.getCategory().equals(Job.JobCategory.GNU_OPENMPI_1_4)){
					out.write(s);
				} else if(job.getCategory().equals(Job.JobCategory.INTEL_OPENMPI_1_4)) {
					out.write(s);
				} else {
					/* TODO: other categories should be supported in the near future */
				}
			}

			out.newLine();

			if(executableName.equals("rungms")) {
				s = "if [[ $? -ne 0 ]] ; then";
				out.write(s);
				out.newLine();
				s = "echo \"=================== INPUT FILE ==================\" >&2";
				out.write(s);
				out.newLine();
				s = "cat ./gamess_input.inp >&2";
				out.write(s);
				out.newLine();
				s = "echo \"=================== ERROR LOG ==================\" >&2";
				out.write(s);
				out.newLine();
				s = "cat ./result/gamess_output.log >&2";
				out.write(s);
				out.newLine();
				s = "	exit -1";
				out.write(s);
				out.newLine();
				s = "fi";
				out.write(s);
				out.newLine();
			}
			else 
			{
				s = "ret=$?";
				out.write(s); out.newLine();
				s = "if [ \"$ret\" -lt 0 ]";
				out.write(s); out.newLine();
				s = "then";
				out.write(s); out.newLine();
				s = "    exit -1";
				out.write(s); out.newLine();
				s = "fi";
				out.write(s); out.newLine();
			}
			
			// uncomment for edison
			s = "zip -r " + Cloud.getInstance().getProp("output.zipfile") + " "
					+ Cloud.getInstance().getProp("output.basedir");
			out.write(s);
//			out.newLine();
			
//			s = "sleep 10";
//			out.write(s);
//			out.newLine();
			s = "'''";
			out.write(s);
			out.newLine();
			s = "jd = saga.job.Description()";
			out.write(s);
			out.newLine();
			
			/* job.getWorkingDir()
			   /EDISON/./TEST/DATA/admin/jobs/2522e961-f1e2-4a0f-9f1a-ce0788f7b947/89735b86-be1b-454f-ba97-6c743ee7550e.job/
			*/
			s = "jd.working_directory = '" + job.getWorkingDir() + "'";
			out.write(s);
			out.newLine();
			
			if (job.getnProcs() > cluster.getRuntime().getTotalCores()) {
				out.close();
				return null;
			} else {
				s = "jd.total_cpu_count = " + job.getnProcs() + ";"; 
				out.write(s);
				out.newLine();
				
				s = "jd.environment = {'LD_LIBRARY_PATH': '/opt/mpi/intel/openmpi-1.4.3/lib:/opt/intel/mkl/10.2.5.035:/lib/em64t:/opt/intel/Compiler/11.1/073/lib/intel64:/opt/intel/Compiler/11.1/073/mkl/lib/em64t:$LD_LIBRARY_PATH'}"; 
				out.write(s);
				out.newLine();
			}
			
			s = "jd.executable      = script";
			out.write(s);
			out.newLine();
//			s = "jd.arguments       = ['-c', script]";
//			out.write(s);
//			out.newLine();
			s = "jd.output          = '" + job.getWorkingDir() + "/" + job.getUuid() + ".out'";
			out.write(s);
			out.newLine();
			s = "jd.error          = '" + job.getWorkingDir() + "/" + job.getUuid() + ".err'";
			out.write(s);
			out.newLine();
			
			s = "jd.queue = '" + cluster.getQueues() + "'";
			out.write(s);
			out.newLine();

//			s = "jd.wall_time_limit = 10";
//			out.write(s);
//			out.newLine();

			s = "try:";
			out.write(s);
			out.newLine();
			
			s = "    js = saga.job.Service('loadl+ssh://%s?cluster=" + cluster.getName() + "' % REMOTE_HOST, session=session)";
			out.write(s);
			out.newLine();
			
			s = "    myjob = js.create_job(jd)";
			out.write(s);
			out.newLine();
			s = "    myjob.run()";
			out.write(s);
			out.newLine();
			
			s = "    print myjob.id";
			out.write(s);
			out.newLine();
			
			s = "except Exception, msg:";
			out.write(s);
			out.newLine();
			
			s = "    import radical.utils.logger as rul";
			out.write(s);
			out.newLine();
			
			s = "    _logger  = rul.getLogger ('saga', 'submit')";
			out.write(s);
			out.newLine();
			
			s = "    _logger.error(msg)";
			out.write(s);
			out.newLine();
			
			s = "    print ''"; // '' means error happens 
			out.write(s);
			out.newLine();
			
//			s = "";
//			out.write(s);
//			out.newLine();
//			s = "";
//			out.write(s);
//			out.newLine();
//			s = "";
//			out.write(s);
//			out.newLine();

			out.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		return filePath;
	}
	
	private String makeJobCancelScriptFile(User user, Job job, String rm, String jobid) {
		String uuid = UUID.randomUUID().toString().replaceAll("-", "");
		String prefix="/tmp/" + uuid; 

		String filePath = prefix + "-loadl-cancel-script";
		
		try {
			BufferedWriter out = new BufferedWriter(new FileWriter(filePath));

			String s = "#!/usr/bin/env python";
			out.write(s);
			out.newLine();
			
			s = "#-*- coding: UTF-8 -*-";
			out.write(s);
			out.newLine();
			
//			s = "import os";
//			out.write(s);
//			out.newLine();
//
//			s = "os.environ['SAGA_VERBOSE'] = 'DEBUG'";
//			out.write(s);
//			out.newLine();
//
//			s = "os.environ['SAGA_LOG_TARGETS'] = '/tmp/" + uuid + ".log'";
//			out.write(s);
//			out.newLine();

			s = "import saga";
			out.write(s);
			out.newLine();

			s = "ctx = saga.Context('ssh')";
			out.write(s);
			out.newLine();

			s = "ctx.user_id = '" + job.getLocalAccount() + "'";
			out.write(s);
			out.newLine();

			s = "session = saga.Session()";
			out.write(s);
			out.newLine();

			s = "session.add_context(ctx)";
			out.write(s);
			out.newLine();

			s = "js = saga.job.Service('" + rm + "', session=session)";
			out.write(s);
			out.newLine();

			s = "myjob = js.get_job('" + jobid + "')";
			out.write(s);
			out.newLine();
			
			s = "try:";
			out.write(s);
			out.newLine();
			
			s = "	myjob.cancel()";
			out.write(s);
			out.newLine();
			
			s = "except:";
			out.write(s);
			out.newLine();

			s = "	print 'cancel error'";
			out.write(s);
			out.newLine();

			out.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		return filePath;
	}

	@Override
	public Job submit(User user, Cluster cluster, Job job) {
		if(job == null || user == null || cluster == null) {
			return null;
		}
		
		String scriptPath = null;
//		pbsTorque.Job submittedJob = null;
		sagaJob submittedJob = null;

		if (job.getType() instanceof Job.JobType) {
			scriptPath = makeJobScriptFile(user, cluster, job);
		} else {
			job.setState(Job.JobState.SUBMISSION_FAILED);
			return job;
		}

		sagaJob j = new sagaJob(job.getUuid(), scriptPath);
		String jobId = null;
		try {
			jobId = j.queue();
			if (jobId != null && !jobId.isEmpty()) {
//				submittedJob = sagaJob.getJobById(jobId, job.getLocalAccount());
				submittedJob = j;
				submittedJob.setStatus("Pending");
			} else {
				job.setState(Job.JobState.SUBMISSION_FAILED);
				return job;
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

//		job.setJobId(submittedJob.getId());
		job.setJobId(jobId);
		job.setLcmState(submittedJob.getStatus());
		job.setState(lcmState2State(submittedJob.getStatus(),
				submittedJob.getExitStatus()));
		
//		if ( job.getState().equals(Job.JobState.SUCCESS) )
//		{
//			SimpleDateFormat df = new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH);
//			try {
//				job.setStartTime(df.parse(submittedJob.getStime()));
//			} catch (ParseException e) {
//				e.printStackTrace();
//				LOG.error(submittedJob.getStime());
//			}
//
//			try {
//				job.setEndTime(df.parse(submittedJob.getComp_time()));
//			} catch (ParseException e) {
//				e.printStackTrace();
//				LOG.error(submittedJob.getComp_time());
//			}
//		}

		return job;
	}

	@Override
	public Job getInformation(User user, Cluster cluster, Job job) {
		if(job == null || user == null || cluster == null) {
			return null;
		}
		
		sagaJob j = null;

		try {
			j = sagaJob.getJobById(job.getJobId(), job.getLocalAccount());
			
//			LOG.info("Job: " + job);
			
			if (j.getId() == null || j.getId().isEmpty()) {
				return null;
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		if ( j.getStatus().equals("N/A") && j.getExitStatus().equals("-1") )
		{
			// when error happens in calling saga-Python, return previous job 
			return job;
		}

		job.setLcmState(j.getStatus());
		job.setState(lcmState2State(j.getStatus(), j.getExitStatus()));

		SimpleDateFormat df = new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH);
		if (job.getState().equals(Job.JobState.RUNNING)
				|| job.getState().equals(Job.JobState.SUCCESS)
				|| job.getState().equals(Job.JobState.FAILED)) {
			try {
				LOG.info(j.getStime()); // debug info
				job.setStartTime(df.parse(j.getStime()));
			} catch (ParseException e) {
				e.printStackTrace();
				LOG.error(j.getStime());
			}
			
			try {
//				LOG.info(j.getComp_time());  // debug info
				job.setEndTime(df.parse(j.getComp_time()));
			} catch (ParseException e) {
				e.printStackTrace();
				LOG.error(j.getComp_time());
			}
		}
		
		return job;
	}

	@Override
	public Job cancel(User user, Cluster cluster, Job job) {
		if(job == null || user == null || cluster == null) {
			return null;
		}
		
		String JobID = job.getJobId();
		// http://www.vogella.com/articles/JavaRegularExpressions/article.html
		Pattern pattern = Pattern.compile("^\\[(.*)\\]-\\[(.*?)\\]$");
		Matcher matcher = pattern.matcher(JobID);
		
		String rm=null;
		if ( matcher.find() == true && matcher.groupCount() == 2)
		{
			rm=matcher.group(1);
		}
		
		StringBuilder excuter = new StringBuilder("python");
		excuter.append(" " + makeJobCancelScriptFile(user, job, rm, JobID));
		String st = excuter.toString();
		
		//Process p = Runtime.getRuntime().exec("qdel " + JobID);
		ProcessBuilder builder = new ProcessBuilder(st.split(" "));
		builder.redirectErrorStream(true);
		
		Process p = null;
		try {
			p = builder.start();
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			p.waitFor();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		BufferedInputStream ef = new BufferedInputStream(p.getInputStream());
		byte[] data = null;
		try {
			data = new byte[ef.available()];
			ef.read(data, 0, ef.available());
			ef.close();
			p.getInputStream().close();
			p.getOutputStream().close();
			p.getErrorStream().close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		/*
		Process p = null;
		try {
			p = Runtime.getRuntime().exec("qdel " + job.getJobId());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		try {
			p.waitFor();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		BufferedInputStream ef = new BufferedInputStream(p.getErrorStream());
		byte[] data = null;
		try {
			data = new byte[ef.available()];
			ef.read(data, 0, ef.available());
			ef.close();
			p.getOutputStream().close();
			p.getErrorStream().close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		*/

		String output = new String(data);
		LOG.info("Canceling " + job.getJobId());
		LOG.info("CANCEL OUTPUT : " + output);

		if (output.length() > 0) {
			// error happens, error messsage exists.
			return null;
		} else {
			job.setState(Job.JobState.CANCELED);
			return job;
		}
	}

	@Override
	public byte[] getErrorLog(User user, Cluster cluster, Job job) throws IOException {
		// TODO Auto-generated method stub
		String errFilePath = job.getWorkingDir() + "/" + job.getUuid() + ".err";
		File file = new File(errFilePath);
		
		InputStream is = new FileInputStream(file);
		long length = file.length();
		byte[] bytes = new byte[(int) length];

		int offset = 0;
		int numRead = 0;
		while (offset < bytes.length
				&& (numRead = is.read(bytes, offset, bytes.length - offset)) >= 0) {
			offset += numRead;
		}

		if (offset < bytes.length) {
			is.close();
			throw new IOException("Could not completely read file "
					+ file.getName());
		}

		is.close();
		return bytes;
	}

	@Override
	public byte[] getOutputLog(User user, Cluster cluster, Job job) throws IOException {
		// TODO Auto-generated method stub
		String errFilePath = job.getWorkingDir() + "/" + job.getUuid() + ".out";
		File file = new File(errFilePath);
		
		InputStream is = new FileInputStream(file);
		long length = file.length();
		byte[] bytes = new byte[(int) length];

		int offset = 0;
		int numRead = 0;
		while (offset < bytes.length
				&& (numRead = is.read(bytes, offset, bytes.length - offset)) >= 0) {
			offset += numRead;
		}

		if (offset < bytes.length) {
			is.close();
			throw new IOException("Could not completely read file "
					+ file.getName());
		}

		is.close();
		return bytes;
	}
	
	@Override
	public String getVersion() {
		return "0.0.1";
	}

	@Override
	public String getName() {
		return "LoadL";
	}

}