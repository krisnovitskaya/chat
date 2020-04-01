package ru.gb.java3.server.client;

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

public class ClientHandler {
    private final long TIMEOUT = 120000; // in millis
    private final NetworkServer networkServer;
    private final Socket clientSocket;

    private ObjectInputStream in;
    private ObjectOutputStream out;


    private String nick;

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
            new Thread(() -> {

                try {
                    authentication();
                    readingMessages();
                } catch (IOException e) {
                    System.out.println("Соединение с клиентом " + nick + " завершено.");
                } finally {
                    closeConnection();
                }

            }).start();

            //сюда попробовать new Thread 120 секунд авторизации
            new Thread(() -> {
                try {
                    Thread.currentThread().sleep(TIMEOUT);
                    if(nick == null){
                        Command authErrorCommand = Command.authErrorCommand("Превышено время ожидания");
                        sendMessage(authErrorCommand);
                        closeConnection();
                        System.out.println("Соединение разорвано по таймауту");
                    }
                } catch (InterruptedException | IOException e) {
                    e.printStackTrace();
                }
            }).start();

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
                        nick = newNick;
                        networkServer.broadcastMessage(Command.messageCommand(null, message), this);
                        sendMessage(command); //смена ника подтверждение
                        List<String> users = networkServer.getAllUserNames();
                        networkServer.broadcastMessage(Command.updateUsersListCommand(users), this);
                    } else {
                        Command errorCommand = Command.errorCommand("Не удалось сменить Nick. Пользователь с таким ником существует или login/pass введены неправильно");
                        sendMessage(errorCommand);
                    }
                    break;
                }
                default:
                    System.err.println("unknown type of command : " + command.getType());
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
        String username = networkServer.getAuthService().getUserNameByLoginAndPass(login, password);
        if(username == null){
            Command authErrorCommand = Command.authErrorCommand("Отсутствует учетная запись с таким логином/паролем");
            sendMessage(authErrorCommand);
            return false;
        } else if (networkServer.isNickBusy(username)){
            Command authErrorCommand = Command.authErrorCommand("Данный пользователь уже авторизован.");
            sendMessage(authErrorCommand);
            return false;
        } else {
            nick = username;
            String message = nick + " зашел в чат!";
            networkServer.broadcastMessage(Command.messageCommand(null, message), this);
            commandData.setUsername(nick);
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
}
