package ru.gb.java3.server.client;

import javafx.util.Pair;
import ru.gb.java3.clientserver.Command;
import ru.gb.java3.clientserver.CommandType;
import ru.gb.java3.clientserver.command.AuthCommand;
import ru.gb.java3.clientserver.command.BroadcastMessageCommand;
import ru.gb.java3.clientserver.command.ChangeNickCommand;
import ru.gb.java3.clientserver.command.PrivateMessageCommand;
import ru.gb.java3.server.NetworkServer;

import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.*;

public class ClientHandler {
    private static final Logger logger = Logger.getLogger(ClientHandler.class.getName());
    private final long TIMEOUT = 120000; // in millis
    private final NetworkServer networkServer;
    private final Socket clientSocket;

    private ObjectInputStream in;
    private ObjectOutputStream out;


    private String nick;

    static {
        setLogger();
    }

    public ClientHandler(NetworkServer networkServer, Socket clientSocket) {
        this.networkServer = networkServer;
        this.clientSocket = clientSocket;

    }


    public void go(){
        doHandle(clientSocket);
    }

    private void doHandle(Socket clientSocket) {
        try {
            out = new ObjectOutputStream(clientSocket.getOutputStream());
            in = new ObjectInputStream(clientSocket.getInputStream());

            //запуск авторизации с последующим чтением ввода в отдельном потоке
            ExecutorService executorService = Executors.newFixedThreadPool(2);
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        authentication();
                        readingMessages();
                    } catch (IOException e) {
                        System.out.println("Соединение с клиентом " + nick + " завершено.");
                        logger.log(Level.SEVERE,"Соединение с клиентом " + nick + " завершено.");
                    } finally {
                        closeConnection();
                    }
                }
            });


            //TIMEOUT
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        executorService.awaitTermination(TIMEOUT, TimeUnit.MILLISECONDS );
                        if(nick == null){
                            Command authErrorCommand = Command.authErrorCommand("Превышено время ожидания");
                            sendMessage(authErrorCommand);
                            closeConnection();
                            System.out.println("Соединение разорвано по таймауту");
                            logger.log(Level.INFO,"Соединение разорвано по таймауту");
                        }
                    } catch (InterruptedException | IOException e) {
                        e.printStackTrace();
                    }
                }
            });

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void closeConnection() {
        try{
            networkServer.unsubscribe(this); //отписаться
            clientSocket.close(); //закрыть сокет и ридеры ввода/вывода
        } catch(IOException e){
            e.printStackTrace();
        }
    }

    private void readingMessages() throws IOException {
        while (true){
            Command command = readCommand();
            if(command == null){
                continue;
            }
            switch (command.getType()){
                case END:
                    System.out.println("received END command");
                    return;
                case PRIVATE_MESSAGE:{
                    PrivateMessageCommand commandData = (PrivateMessageCommand) command.getData();
                    String receiver = commandData.getReceiver();
                    String message = commandData.getMessage();
                    networkServer.sendMessage(receiver, Command.messageCommand(nick, message)); //получатель отправитель сообщение
                    break;
                }
                case BROADCAST_MESSAGE:{
                    BroadcastMessageCommand commandData = (BroadcastMessageCommand) command.getData();
                    String message = commandData.getMessage();
                    networkServer.broadcastMessage(Command.messageCommand(nick, message), this);
                    break;
                }
                case CHANGE_NICK:{
                    ChangeNickCommand commandData = (ChangeNickCommand) command.getData();
                    String login = commandData.getLogin();
                    String pass = commandData.getPassword();
                    String newNick = commandData.getUsername();
                    boolean changeSuccessful = networkServer.getAuthService().changeCurrentNickname(login, pass, newNick);
                    if(changeSuccessful){
                        String message = nick + " сменил ник на " + newNick;
                        logger.log(Level.INFO,message);
                        nick = newNick;
                        networkServer.broadcastMessage(Command.messageCommand(null, message), this);

                        sendMessage(command); //смена ника подтверждение
                        List<String> users = networkServer.getAllUserNames();
                        networkServer.broadcastMessage(Command.updateUsersListCommand(users), this);
                    } else {
                        Command errorCommand = Command.errorCommand("Не удалось сменить Nick. Пользователь с таким ником существует или login/pass введены неправильно");
                        logger.log(Level.WARNING,"Пользователь " + nick + "Не удалось сменить Nick. Пользователь с таким ником существует или login/pass введены неправильно");
                        sendMessage(errorCommand);
                    }
                    break;
                }
                default:
                    System.err.println("unknown type of command : " + command.getType());
                    logger.log(Level.SEVERE,"unknown type of command : " + command.getType());

            }
        }
    }

    private Command readCommand() throws IOException {
        try {
            return (Command) in.readObject();
        } catch (ClassNotFoundException e) {
            String errorMessage = "Unknown type of object from client";
            System.err.println(errorMessage);
            e.printStackTrace();
            sendMessage(Command.errorCommand(errorMessage));
            return null;
        }
    }

    private void authentication() throws IOException {
        while(true) {
            Command command = readCommand();
            if(command == null){
                continue;
            }
            if(command.getType() == CommandType.AUTH){
                boolean successfulAuth = processAuthCommand(command);
                if(successfulAuth){
                    logger.log(Level.INFO,"Успешная авторизация");
                    return;
                }
            } else {
                System.err.println("Unknown type of command for authprocess: " + command.getType());
            }
        }
    }

    private boolean processAuthCommand(Command command) throws IOException {
        AuthCommand commandData = (AuthCommand) command.getData();
        String login = commandData.getLogin();
        String password = commandData.getPassword();
        //String username = networkServer.getAuthService().getUserNameByLoginAndPass(login, password);
        Pair<Integer, String> username = networkServer.getAuthService().getUserNameByLoginAndPass(login, password);
        if(username == null){
            Command authErrorCommand = Command.authErrorCommand("Отсутствует учетная запись с таким логином/паролем");
            logger.log(Level.INFO,"Отсутствует учетная запись с введенным логином/паролем");
            sendMessage(authErrorCommand);
            return false;
        } else if (networkServer.isNickBusy(username.getValue())){
            Command authErrorCommand = Command.authErrorCommand("Данный пользователь уже авторизован.");
            logger.log(Level.INFO,"Попытка авторизации, авторизованного пользователя");
            sendMessage(authErrorCommand);
            return false;
        } else {
            nick = username.getValue();
            String message = nick + " зашел в чат!";
            networkServer.broadcastMessage(Command.messageCommand(null, message), this);
            logger.log(Level.INFO,message);
            commandData.setUsername(nick);
            commandData.setID(username.getKey());
            sendMessage(command); //авторизация
            networkServer.subscribe(this);
            return true;
        }

    }

    public void sendMessage(Command command) throws IOException {
        out.writeObject(command);
    }

    public String getUserName() {
        return nick;
    }
    private static void setLogger() {
        Handler handler = null;
        try {
            handler = new FileHandler("clienthandlerlog.log", true);
        } catch (IOException e) {
            e.printStackTrace();
        }
        handler.setFormatter(new SimpleFormatter());
        logger.addHandler(handler);
        logger.setLevel(Level.FINEST);
        logger.getHandlers()[0].setLevel(Level.INFO);

    }
}
